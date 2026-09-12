package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceAnalyticsPayload
import com.afrithecus.brainbox.api.attendance.web.AttendancePerformanceAnalyticsPayload
import com.afrithecus.brainbox.api.attendance.web.AttendancePerformanceRiskSummaryPayload
import com.afrithecus.brainbox.api.attendance.web.AttendancePerformanceTrendPointPayload
import com.afrithecus.brainbox.api.attendance.web.AttendanceRecordPayload
import com.afrithecus.brainbox.api.attendance.web.ChildAttendancePerformancePayload
import com.afrithecus.brainbox.api.attendance.web.ChildAttendanceTrendPointPayload
import com.afrithecus.brainbox.api.attendance.web.ParentAttendanceRecordPayload
import com.afrithecus.brainbox.api.attendance.web.StudentAttendancePerformancePayload
import com.afrithecus.brainbox.api.attendance.web.AttendanceTrendPointPayload
import com.afrithecus.brainbox.api.attendance.web.ChronicAbsenteeismAlertPayload
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.live.repository.LiveAttendanceRepository
import com.afrithecus.brainbox.api.mastery.MasteryService
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Teacher attendance register (doc 04 §4, docs/ongoing/api_attendance_changes.md).
 *
 * A register is bucketed by its calendar day in the school zone and keyed on
 * (class, day, student), so an offline replay converges instead of duplicating.
 * Mark-by-exception is preserved: roster learners missing from a full-register
 * post are recorded ABSENT defensively. Absent/late learners fan out a push to
 * their linked parent (the client also writes a same-device local alert).
 */
@Service
class AttendanceService(
    private val recordRepository: AttendanceRecordRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val liveAttendanceRepository: LiveAttendanceRepository,
    private val examSubmissionRepository: ExamSubmissionRepository,
    private val masteryService: MasteryService,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZoneId: String,
) {

    private val zone: ZoneId get() = ZoneId.of(schoolZoneId)

    @Transactional(readOnly = true)
    fun register(current: CurrentUser, classIdRaw: String, dateMillis: Long): List<AttendanceRecordPayload> {
        val user = user(current)
        val clazz = readableClass(user, classIdRaw)
        return payloads(clazz, recordRepository.findAllByClassIdAndAttendanceDate(clazz.id, dayOf(dateMillis)))
    }

    @Transactional
    fun submit(current: CurrentUser, records: List<AttendanceRecordPayload>): List<AttendanceRecordPayload> {
        val user = user(current)
        if (records.isEmpty()) return emptyList()
        val persisted = mutableListOf<AttendanceRecordEntity>()
        for ((classIdRaw, classRecords) in records.groupBy { it.classId }) {
            val clazz = classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
                ?: throw notFound("Class not found")
            requireWriter(user, clazz)
            val day = dayOf(classRecords.first().date)
            val existing = recordRepository.findAllByClassIdAndAttendanceDate(clazz.id, day).associateBy { it.studentId }
            val payloadIds = classRecords.map { parseUuid(it.studentId, "studentId") }.toSet()
            for (record in classRecords) {
                val studentId = parseUuid(record.studentId, "studentId")
                val row = existing[studentId] ?: AttendanceRecordEntity().apply {
                    classId = clazz.id
                    this.studentId = studentId
                    attendanceDate = day
                }
                val previous = row.status
                apply(row, record.status, record.notes, clazz, user)
                val saved = recordRepository.save(row)
                persisted += saved
                if (previous != saved.status && saved.status in NOTIFIABLE) {
                    notifyParent(studentId, clazz, saved)
                }
            }
            // Defensive mark-by-exception: anyone absent from the full register is ABSENT.
            val roster = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }
            for (missing in roster.filter { it !in payloadIds }) {
                val row = existing[missing] ?: AttendanceRecordEntity().apply {
                    classId = clazz.id
                    studentId = missing
                    attendanceDate = day
                }
                val previous = row.status
                apply(row, AttendanceStatus.ABSENT.name, null, clazz, user)
                val saved = recordRepository.save(row)
                persisted += saved
                if (previous != AttendanceStatus.ABSENT) notifyParent(missing, clazz, saved)
            }
        }
        val byClassDay = persisted.groupBy { it.classId to it.attendanceDate }
        return byClassDay.flatMap { (key, rows) ->
            val clazz = classRepository.findById(key.first).orElse(null) ?: return@flatMap emptyList()
            payloads(clazz, rows)
        }
    }

    // ------------------------------------------------------------ analytics

    /**
     * Class attendance analytics over [startMillis, endMillis] (doc 04 §4.4).
     * Attendance rate = (PRESENT + LATE) / (PRESENT + LATE + ABSENT); EXCUSED is
     * excluded from both sides. The weekly heatmap keys 1=Monday..7=Sunday.
     */
    @Transactional(readOnly = true)
    fun analytics(current: CurrentUser, classIdRaw: String, startMillis: Long, endMillis: Long): AttendanceAnalyticsPayload {
        val clazz = readableClass(user(current), classIdRaw)
        val from = dayOf(minOf(startMillis, endMillis))
        val to = dayOf(maxOf(startMillis, endMillis))
        val records = recordRepository.findAllByClassIdAndAttendanceDateBetween(clazz.id, from, to)
        val roster = rosterIds(clazz, records)
        val byDay = records.groupBy { it.attendanceDate }.toSortedMap()

        val dailyRates = byDay.mapNotNull { (day, dayRecords) ->
            val rate = attendanceRate(dayRecords, roster.size) ?: return@mapNotNull null
            day to rate
        }
        val trend = dailyRates.map { (day, rate) -> AttendanceTrendPointPayload(millisOf(day), rate) }
        val average = if (dailyRates.isEmpty()) 0.0 else dailyRates.map { it.second }.average()
        val weekly = dailyRates.groupBy { it.first.dayOfWeek.value }
            .mapValues { (_, pairs) -> round2(pairs.map { it.second }.average()) }
            .toSortedMap()
        val monthly = dailyRates.groupBy { it.first.dayOfMonth }
            .mapValues { (_, pairs) -> round2(pairs.map { it.second }.average()) }
            .toSortedMap()
        return AttendanceAnalyticsPayload(
            classId = clazz.id.toString(),
            averageAttendance = round2(average),
            trendPoints = trend,
            chronicAbsenteeismAlerts = chronicAlerts(clazz, records),
            weeklyHeatmap = weekly,
            monthlyHeatmap = monthly,
        )
    }

    private fun chronicAlerts(clazz: TeacherClassEntity, records: List<AttendanceRecordEntity>): List<ChronicAbsenteeismAlertPayload> {
        val students = userRepository.findAllById(records.map { it.studentId }).associateBy { it.id }
        return records.groupBy { it.studentId }.mapNotNull { (studentId, rows) ->
            val counted = rows.count { it.status != AttendanceStatus.EXCUSED }
            val attended = rows.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
            val rate = if (counted == 0) 100.0 else round2(attended * 100.0 / counted)
            val missed = rows.count { it.status == AttendanceStatus.ABSENT }
            if (rate >= CHRONIC_RATE && missed < CHRONIC_MISSED) return@mapNotNull null
            val lastAbsent = rows.filter { it.status == AttendanceStatus.ABSENT }.maxOfOrNull { it.attendanceDate }
            ChronicAbsenteeismAlertPayload(
                studentId = studentId.toString(),
                studentName = students[studentId]?.name.orEmpty(),
                attendancePercentage = rate,
                missedDaysCount = missed,
                lastAbsentDate = lastAbsent?.let(::millisOf) ?: 0L,
                severity = when {
                    rate < 70.0 -> "HIGH"
                    rate < CHRONIC_RATE -> "MEDIUM"
                    else -> "LOW"
                },
            )
        }.sortedBy { it.attendancePercentage }.take(20)
    }

    /** (present + late) / counted, or null when the day has no countable records. */
    private fun attendanceRate(dayRecords: List<AttendanceRecordEntity>, rosterSize: Int): Double? {
        val counted = dayRecords.count { it.status != AttendanceStatus.EXCUSED }
        if (counted == 0) return null
        val attended = dayRecords.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
        return round2(attended * 100.0 / counted)
    }

    private fun rosterIds(clazz: TeacherClassEntity, records: List<AttendanceRecordEntity>): Set<UUID> {
        val roster = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }.toSet()
        return if (roster.isNotEmpty()) roster else records.map { it.studentId }.toSet()
    }

    private fun round2(value: Double): Double = kotlin.math.round(value * 100) / 100.0

    // ------------------------------------------------------------ parent

    /** Parent view of a linked child's attendance, most recent first (docs/ongoing §parent). */
    @Transactional(readOnly = true)
    fun parentAttendance(current: CurrentUser, childIdRaw: String): List<ParentAttendanceRecordPayload> {
        val child = linkedChild(current, childIdRaw)
        val sessions = liveAttendanceRepository.findAllByStudentIdOrderByRecordedAtDesc(child.id)
            .filter { it.joinedAt != null }
            .groupBy { LocalDate.ofInstant(it.joinedAt!!, zone) }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.recordedAt }!! }
        return recordRepository.findAllByStudentIdOrderByAttendanceDateDesc(child.id).map { row ->
            val session = sessions[row.attendanceDate]
            ParentAttendanceRecordPayload(
                date = millisOf(row.attendanceDate),
                status = row.status.name,
                reason = row.notes,
                checkInTime = session?.joinedAt?.toEpochMilli(),
                leaveTime = session?.leftAt?.toEpochMilli(),
                durationMinutes = session?.durationMinutes ?: 0,
                isPresent = session?.isPresent ?: (row.status != AttendanceStatus.ABSENT),
                classId = row.classId.toString(),
                userId = child.id.toString(),
            )
        }
    }

    /** Server-computed attendance/performance intelligence for a linked child (doc 07 §5.2). */
    @Transactional(readOnly = true)
    fun parentPerformance(current: CurrentUser, childIdRaw: String): ChildAttendancePerformancePayload {
        val child = linkedChild(current, childIdRaw)
        val today = LocalDate.ofInstant(clock.instant(), zone)
        val from = today.minusDays(DEFAULT_WINDOW_DAYS - 1)
        val records = recordRepository.findAllByStudentIdOrderByAttendanceDateDesc(child.id)
            .filter { it.attendanceDate >= from && it.attendanceDate <= today }
        val counted = records.count { it.status != AttendanceStatus.EXCUSED }
        val attended = records.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
        val percentage = if (counted == 0) 100.0 else round2(attended * 100.0 / counted)
        val startInstant = from.atStartOfDay(zone).toInstant()
        val submissions = examSubmissionRepository.findAllByUserId(child.id)
        val inWindow = submissions.filter { it.submittedAt >= startInstant }
        val scores = (if (inWindow.isNotEmpty()) inWindow else submissions).map { it.percentage.toDouble() }
        val averageScore = if (scores.isEmpty()) 0.0 else round2(scores.average())
        val benchmark = benchmarkAverage(child)
        val tier = riskTier(percentage, averageScore)
        val daily = records.groupBy { it.attendanceDate }.toSortedMap().map { (day, rows) ->
            val dayCounted = rows.count { it.status != AttendanceStatus.EXCUSED }
            val dayAttended = rows.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
            ChildAttendanceTrendPointPayload(
                date = millisOf(day),
                attendancePercentage = if (dayCounted == 0) 0.0 else round2(dayAttended * 100.0 / dayCounted),
                averageScore = averageScore,
            )
        }
        return ChildAttendancePerformancePayload(
            attendancePercentage = percentage,
            averageScore = averageScore,
            benchmarkAverage = benchmark,
            riskTier = tier,
            impactInsight = impactInsight(tier),
            missedDaysCount = records.count { it.status == AttendanceStatus.ABSENT },
            trendSeries = daily,
        )
    }

    private fun benchmarkAverage(child: UserEntity): Double {
        val peerIds = linkedSetOf<UUID>()
        membershipRepository.findAllByStudentId(child.id).forEach { membership ->
            membershipRepository.findAllByClassId(membership.classId)
                .forEach { peerIds += it.studentId }
        }
        peerIds.remove(child.id)
        if (peerIds.isEmpty() && child.schoolId != null && child.gradeLevel != null) {
            userRepository.findAllBySchoolIdAndGradeLevelAndRole(child.schoolId!!, child.gradeLevel!!, Role.STUDENT)
                .forEach { if (it.id != child.id) peerIds += it.id }
        }
        if (peerIds.isEmpty()) return 0.0
        val scores = examSubmissionRepository.findAllByUserIdIn(peerIds).map { it.percentage.toDouble() }
        return if (scores.isEmpty()) 0.0 else round2(scores.average())
    }

    private fun impactInsight(tier: String): String = when (tier) {
        "CRITICAL" -> "Attendance is critically low and scores are suffering. Please contact the class teacher."
        "HIGH" -> "Frequent absences are linked to lower scores. A follow-up is recommended."
        "MEDIUM" -> "Attendance dips are starting to affect performance. Keep monitoring."
        else -> "Attendance and performance are on track."
    }

    private fun linkedChild(current: CurrentUser, childIdRaw: String): UserEntity {
        val child = userRepository.findById(parseUuid(childIdRaw, "childId")).orElse(null)
            ?: throw notFound("Child not found")
        if (current.role != Role.ADMIN && child.parentUserId != current.userId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your linked child")
        }
        return child
    }

    // ------------------------------------------------------------ auto-mark

    /**
     * Populate a day's register from a live-class session (doc 04 §4.1). Only
     * attended learners are written (PRESENT, flagged auto) and a manually filed
     * status is never overwritten; everyone else is left for the teacher.
     */
    @Transactional
    fun autoMark(current: CurrentUser, classIdRaw: String, liveClassIdRaw: String, dateMillis: Long): List<AttendanceRecordPayload> {
        val user = user(current)
        val clazz = classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
            ?: throw notFound("Class not found")
        requireWriter(user, clazz)
        val liveClassId = parseUuid(liveClassIdRaw, "liveClassId")
        val day = dayOf(dateMillis)
        val attended = liveAttendanceRepository.findAllByClassId(liveClassId)
            .filter { countsAsAttended(it) }
            .map { it.studentId }
            .toSet()
        if (attended.isNotEmpty()) {
            val roster = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }.toSet()
            val existing = recordRepository.findAllByClassIdAndAttendanceDate(clazz.id, day).associateBy { it.studentId }
            for (studentId in attended.filter { it in roster }) {
                val current = existing[studentId]
                if (current != null && !current.isAutoFromLiveClass) continue
                val row = current ?: AttendanceRecordEntity().apply {
                    classId = clazz.id
                    this.studentId = studentId
                    attendanceDate = day
                }
                row.status = AttendanceStatus.PRESENT
                row.schoolId = clazz.schoolId
                row.recordedBy = user.id
                row.recordedByName = user.name
                row.isAutoFromLiveClass = true
                recordRepository.save(row)
            }
        }
        return payloads(clazz, recordRepository.findAllByClassIdAndAttendanceDate(clazz.id, day))
    }

    private fun countsAsAttended(row: com.afrithecus.brainbox.api.live.entity.LiveAttendanceEntity): Boolean {
        if (!row.isPresent) return false
        // Sessions under 5 minutes do not count (client rule); in-progress counts.
        if (row.leftAt != null && row.durationMinutes < MIN_PRESENT_MINUTES) return false
        return true
    }

    // ------------------------------------------------------------ performance

    /**
     * Attendance-vs-performance intelligence (doc 04 §4.4). Attendance percentage
     * uses the same rate rule as analytics; the score comes from the learner's exam
     * submissions in the window (falling back to all-time).
     */
    @Transactional(readOnly = true)
    fun performance(current: CurrentUser, classIdRaw: String, startMillis: Long, endMillis: Long): AttendancePerformanceAnalyticsPayload {
        val clazz = readableClass(user(current), classIdRaw)
        val from = dayOf(minOf(startMillis, endMillis))
        val to = dayOf(maxOf(startMillis, endMillis))
        val records = recordRepository.findAllByClassIdAndAttendanceDateBetween(clazz.id, from, to)
        val roster = rosterIds(clazz, records).toMutableSet()
        val unknown = records.map { it.studentId }.filter { it !in roster }
        roster += unknown
        val attendanceByStudent = records.groupBy { it.studentId }.mapValues { (_, rows) ->
            val counted = rows.count { it.status != AttendanceStatus.EXCUSED }
            if (counted == 0) 100.0 else round2(rows.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE } * 100.0 / counted)
        }
        val students = userRepository.findAllById(roster).associateBy { it.id }
        val startInstant = from.atStartOfDay(zone).toInstant()
        val endInstant = to.plusDays(1).atStartOfDay(zone).toInstant()

        val rows = roster.mapNotNull { studentId ->
            val student = students[studentId] ?: return@mapNotNull null
            val percentage = attendanceByStudent[studentId] ?: 100.0
            val submissions = examSubmissionRepository.findAllByUserId(studentId)
            val inWindow = submissions.filter { it.submittedAt >= startInstant && it.submittedAt < endInstant }
            val scores = (if (inWindow.isNotEmpty()) inWindow else submissions).map { it.percentage.toDouble() }
            val averageScore = if (scores.isEmpty()) 0.0 else round2(scores.average())
            val half = (from.toEpochDay() + to.toEpochDay()) / 2
            val first = inWindow.filter { it.submittedAt < java.time.Instant.ofEpochSecond(half * 86400) }.map { it.percentage.toDouble() }
            val second = inWindow.filter { it.submittedAt >= java.time.Instant.ofEpochSecond(half * 86400) }.map { it.percentage.toDouble() }
            val masteryDelta = if (first.isNotEmpty() && second.isNotEmpty()) round2(second.average() - first.average()) else 0.0
            val studentRecords = records.filter { it.studentId == studentId }
            val trend = attendanceTrend(studentRecords, from, to)
            StudentAttendancePerformancePayload(
                studentId = studentId.toString(),
                studentName = student.name,
                attendancePercentage = percentage,
                averageScore = averageScore,
                masteryLevel = round2(runCatching { masteryService.overallScore(studentId) }.getOrDefault(0.0)),
                quadrant = quadrant(percentage, averageScore),
                riskTier = riskTier(percentage, averageScore),
                trend = trend,
                missedAssessmentsCount = studentRecords.count { it.status == AttendanceStatus.ABSENT },
                masteryDelta = masteryDelta,
            )
        }.sortedBy { it.studentName.lowercase() }

        val classAverageAttendance = if (rows.isEmpty()) 0.0 else round2(rows.map { it.attendancePercentage }.average())
        val classAverageScore = if (rows.isEmpty()) 0.0 else round2(rows.map { it.averageScore }.average())
        val correlation = pearson(rows.map { it.attendancePercentage }, rows.map { it.averageScore })
        val trendSeries = attendanceTrendSeries(clazz, records, from, to, classAverageScore)
        return AttendancePerformanceAnalyticsPayload(
            classId = clazz.id.toString(),
            startDate = millisOf(from),
            endDate = millisOf(to),
            classAverageAttendance = classAverageAttendance,
            classAverageScore = classAverageScore,
            correlation = correlation,
            riskSummary = AttendancePerformanceRiskSummaryPayload(
                low = rows.count { it.riskTier == "LOW" },
                medium = rows.count { it.riskTier == "MEDIUM" },
                high = rows.count { it.riskTier == "HIGH" },
                critical = rows.count { it.riskTier == "CRITICAL" },
            ),
            students = rows,
            trendSeries = trendSeries,
        )
    }

    private fun attendanceTrend(rows: List<AttendanceRecordEntity>, from: LocalDate, to: LocalDate): String {
        if (rows.isEmpty()) return "STABLE"
        val midpoint = from.plusDays((to.toEpochDay() - from.toEpochDay()) / 2)
        val first = rateOf(rows.filter { it.attendanceDate <= midpoint })
        val second = rateOf(rows.filter { it.attendanceDate > midpoint })
        if (first == null || second == null) return "STABLE"
        return when {
            second > first + 2.0 -> "UP"
            second < first - 2.0 -> "DOWN"
            else -> "STABLE"
        }
    }

    private fun rateOf(rows: List<AttendanceRecordEntity>): Double? {
        val counted = rows.count { it.status != AttendanceStatus.EXCUSED }
        if (counted == 0) return null
        return rows.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE } * 100.0 / counted
    }

    private fun quadrant(attendance: Double, score: Double): String = when {
        attendance >= HIGH_ATTENDANCE && score >= HIGH_PERFORMANCE -> "HIGH_ATTEND_HIGH_PERF"
        attendance >= HIGH_ATTENDANCE -> "HIGH_ATTEND_LOW_PERF"
        score >= HIGH_PERFORMANCE -> "LOW_ATTEND_HIGH_PERF"
        else -> "LOW_ATTEND_LOW_PERF"
    }

    private fun riskTier(attendance: Double, score: Double): String = when {
        attendance < 70.0 && score < 50.0 -> "CRITICAL"
        attendance < 80.0 || score < 55.0 -> "HIGH"
        attendance < 90.0 || score < 65.0 -> "MEDIUM"
        else -> "LOW"
    }

    private fun attendanceTrendSeries(
        clazz: TeacherClassEntity,
        records: List<AttendanceRecordEntity>,
        from: LocalDate,
        to: LocalDate,
        classAverageScore: Double,
    ): List<AttendancePerformanceTrendPointPayload> {
        val rosterSize = rosterIds(clazz, records).size
        return records.groupBy { it.attendanceDate }.toSortedMap().map { (day, dayRecords) ->
            AttendancePerformanceTrendPointPayload(
                date = millisOf(day),
                attendancePercentage = attendanceRate(dayRecords, rosterSize) ?: 0.0,
                averageScore = classAverageScore,
            )
        }
    }

    private fun pearson(x: List<Double>, y: List<Double>): Double {
        if (x.size < 2 || x.size != y.size) return 0.0
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx2 = 0.0
        var dy2 = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx
            val dy = y[i] - my
            num += dx * dy
            dx2 += dx * dx
            dy2 += dy * dy
        }
        val denom = kotlin.math.sqrt(dx2 * dy2)
        return if (denom == 0.0) 0.0 else round2((num / denom).coerceIn(-1.0, 1.0))
    }

    // ------------------------------------------------------------ internals

    private fun apply(
        row: AttendanceRecordEntity,
        statusRaw: String,
        notes: String?,
        clazz: TeacherClassEntity,
        user: UserEntity,
    ) {
        row.status = statusOf(statusRaw)
        row.notes = notes
        row.schoolId = clazz.schoolId
        row.recordedBy = user.id
        row.recordedByName = user.name
        row.isAutoFromLiveClass = false
    }

    private fun payloads(clazz: TeacherClassEntity, rows: List<AttendanceRecordEntity>): List<AttendanceRecordPayload> {
        if (rows.isEmpty()) return emptyList()
        val students = userRepository.findAllById(rows.map { it.studentId }).associateBy { it.id }
        return rows.sortedBy { students[it.studentId]?.name?.lowercase() ?: "" }.map { row ->
            AttendanceRecordPayload(
                id = row.id.toString(),
                classId = clazz.id.toString(),
                studentId = row.studentId.toString(),
                studentName = students[row.studentId]?.name.orEmpty(),
                date = millisOf(row.attendanceDate),
                status = row.status.name,
                notes = row.notes,
                recordedBy = row.recordedByName.orEmpty(),
                isAutoFromLiveClass = row.isAutoFromLiveClass,
            )
        }
    }

    private fun notifyParent(studentId: UUID, clazz: TeacherClassEntity, row: AttendanceRecordEntity) {
        val student = userRepository.findById(studentId).orElse(null) ?: return
        val parentId = student.parentUserId ?: return
        val dayLabel = DATE_FORMAT.format(row.attendanceDate)
        val absent = row.status == AttendanceStatus.ABSENT
        val title = if (absent) "Absence Alert: " + student.name else "Late Arrival: " + student.name
        val message = if (absent) {
            student.name + " was marked absent on " + dayLabel + ". Please follow up."
        } else {
            student.name + " was marked late on " + dayLabel + "."
        }
        notificationService.notifyUser(
            userId = parentId,
            title = title,
            message = message,
            type = NotificationType.ATTENDANCE,
            urgency = if (absent) NotificationUrgency.HIGH else NotificationUrgency.NORMAL,
            priority = NotificationPriority.HIGH,
            actionRoute = "student_report/" + studentId,
            actionLabel = "View report",
            metadata = mapOf(
                "studentId" to studentId.toString(),
                "classId" to clazz.id.toString(),
                "status" to row.status.name,
            ),
        )
    }

    private fun readableClass(user: UserEntity, classIdRaw: String): TeacherClassEntity {
        val clazz = classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
            ?: throw notFound("Class not found")
        if (!canAccess(user, clazz)) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        return clazz
    }

    private fun requireWriter(user: UserEntity, clazz: TeacherClassEntity) {
        if (!canAccess(user, clazz)) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        val allowed = user.role == Role.ADMIN ||
            user.subRole == SubRole.ICT_ADMIN ||
            user.subRole == SubRole.GRADE_COORDINATOR ||
            (user.subRole == SubRole.CTEACHER && clazz.teacherUserId == user.id)
        if (!allowed) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only class teachers and grade coordinators can file attendance")
        }
    }

    private fun canAccess(user: UserEntity, clazz: TeacherClassEntity): Boolean {
        if (user.role == Role.ADMIN) return true
        if (clazz.teacherUserId == user.id) return true
        val schoolWide = user.subRole == SubRole.ICT_ADMIN || user.subRole == SubRole.GRADE_COORDINATOR
        return schoolWide && (clazz.schoolId == null || clazz.schoolId == user.schoolId)
    }

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    private fun statusOf(raw: String): AttendanceStatus =
        AttendanceStatus.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw invalidArgument("Unknown attendance status: " + raw)

    private fun dayOf(millis: Long): LocalDate = LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone)

    private fun millisOf(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private companion object {
        const val CHRONIC_RATE = 80.0
        const val CHRONIC_MISSED = 3
        const val MIN_PRESENT_MINUTES = 5
        const val DEFAULT_WINDOW_DAYS = 30L
        const val HIGH_ATTENDANCE = 90.0
        const val HIGH_PERFORMANCE = 65.0
        val NOTIFIABLE = setOf(AttendanceStatus.ABSENT, AttendanceStatus.LATE)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.US)
    }
}
