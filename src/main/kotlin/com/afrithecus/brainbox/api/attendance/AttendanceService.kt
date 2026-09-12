package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.attendance.web.AttendanceRecordPayload
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
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
        val NOTIFIABLE = setOf(AttendanceStatus.ABSENT, AttendanceStatus.LATE)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.US)
    }
}
