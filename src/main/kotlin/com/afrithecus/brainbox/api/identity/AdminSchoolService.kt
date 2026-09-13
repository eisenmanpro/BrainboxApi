package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.gradebook.repository.GradebookEntryRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolBackupEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolBackupRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.AuditLogEntryPayload
import com.afrithecus.brainbox.api.identity.web.BackupResultPayload
import com.afrithecus.brainbox.api.identity.web.ClassAttendanceSummaryPayload
import com.afrithecus.brainbox.api.identity.web.ClassRankingPayload
import com.afrithecus.brainbox.api.identity.web.GradeConfigSummaryPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAnalyticsPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAttendanceOverviewPayload
import com.afrithecus.brainbox.api.identity.web.SubjectSchoolPerformancePayload
import com.afrithecus.brainbox.api.identity.web.SystemSettingsPayload
import com.afrithecus.brainbox.api.identity.web.TeacherPerformancePayload
import com.afrithecus.brainbox.api.identity.web.UserApprovalRequestPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/**
 * School administration reads/writes for the ICT admin (docs/ongoing/api_admin_changes.md):
 * analytics, grade configs, pending approvals, attendance overview, system settings,
 * audit logs and an explicit logical backup. Every call derives the school access from
 * the token (platform ADMIN any school, ICT_ADMIN only their own).
 */
@Service
class AdminSchoolService(
    private val schoolRepository: SchoolRepository,
    private val userRepository: UserRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val gradebookEntryRepository: GradebookEntryRepository,
    private val attendanceRepository: AttendanceRecordRepository,
    private val settingsRepository: SchoolSystemSettingsRepository,
    private val configRepository: SchoolConfigRepository,
    private val backupRepository: SchoolBackupRepository,
    private val auditLogService: AuditLogService,
    private val access: AdminSchoolAccess,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
) {

    @Transactional(readOnly = true)
    fun analytics(current: CurrentUser, schoolIdRaw: String): SchoolAnalyticsPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val school = schoolRepository.findById(schoolId).orElseThrow { notFound("School not found") }
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val members = classes.associate { it.id to membershipRepository.findAllByClassId(it.id) }
        val entries = if (classes.isEmpty()) emptyList() else gradebookEntryRepository.findAllByClassIdIn(classes.map { it.id })
        val users = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val percentages = entries.map { it.percentage }
        val byClass = entries.groupBy { it.classId }
        val classRankings = classes.map { clazz ->
            val rows = byClass[clazz.id].orEmpty()
            ClassRankingPayload(
                classId = clazz.id.toString(),
                className = clazz.name,
                average = if (rows.isEmpty()) 0.0 else round1(rows.map { it.percentage }.average()),
                rank = 0,
                totalStudents = members[clazz.id]?.size ?: 0,
            )
        }.sortedByDescending { it.average }.mapIndexed { index, row -> row.copy(rank = index + 1) }
        val bySubject = entries.groupBy { entry -> classes.find { it.id == entry.classId }?.subject ?: "General" }
        val subjectPerformance = bySubject.map { (subject, rows) ->
            val values = rows.map { it.percentage }
            SubjectSchoolPerformancePayload(
                subject = subject,
                schoolMean = round1(values.average()),
                topScore = values.max(),
                lowestScore = values.min(),
                passRate = round1(values.count { it >= PASS_MARK } * 100.0 / values.size),
                studentCount = rows.map { it.studentId }.distinct().size,
            )
        }.sortedByDescending { it.schoolMean }
        val teacherPerformance = users.filter { it.role == Role.TEACHER }.map { teacher ->
            val owned = classes.filter { it.teacherUserId == teacher.id }
            val rows = entries.filter { entry -> owned.any { it.id == entry.classId } }
            TeacherPerformancePayload(
                teacherId = teacher.id.toString(),
                teacherName = teacher.name,
                average = if (rows.isEmpty()) 0.0 else round1(rows.map { it.percentage }.average()),
                subjects = owned.map { it.subject }.distinct(),
                classesCount = owned.size,
                studentsCount = owned.sumOf { members[it.id]?.size ?: 0 },
            )
        }.sortedByDescending { it.average }
        return SchoolAnalyticsPayload(
            schoolId = schoolId.toString(),
            schoolName = school.name,
            term = termLabel(),
            year = LocalDate.now(zone()).year,
            overallPerformance = if (percentages.isEmpty()) 0.0 else round1(percentages.average()),
            classRankings = classRankings,
            subjectPerformance = subjectPerformance,
            teacherPerformance = teacherPerformance,
            totalStudents = users.count { it.role == Role.STUDENT },
            totalTeachers = users.count { it.role == Role.TEACHER },
            totalClasses = classes.size,
            gradeDistribution = percentages.groupingBy { bandOf(it) }.eachCount(),
            generatedAt = clock.millis(),
        )
    }

    @Transactional(readOnly = true)
    fun grades(current: CurrentUser, schoolIdRaw: String): List<GradeConfigSummaryPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val coordinators = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.role == Role.TEACHER && it.subRole == SubRole.GRADE_COORDINATOR }
        return classes.groupBy { it.gradeLevel }.map { (grade, rows) ->
            val classTeacher = rows.firstOrNull()?.teacherUserId?.let { userRepository.findById(it).orElse(null)?.name }
            GradeConfigSummaryPayload(
                configId = "grade_" + grade.lowercase().replace(Regex("[^a-z0-9]+"), "_"),
                gradeLevel = grade,
                displayName = grade,
                classTeacherName = classTeacher,
                coordinatorName = coordinators.firstOrNull { it.gradeLevel == grade }?.name,
                active = rows.any { it.isActive },
            )
        }.sortedBy { it.gradeLevel }
    }

    @Transactional(readOnly = true)
    fun approvals(current: CurrentUser, schoolIdRaw: String): List<UserApprovalRequestPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val school = schoolRepository.findById(schoolId).orElseThrow { notFound("School not found") }
        return userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.verificationStatus == AccountStatus.PENDING_VERIFICATION }
            .map {
                UserApprovalRequestPayload(
                    userId = it.id.toString(),
                    name = it.name,
                    phoneNumber = it.phoneNumber.orEmpty(),
                    schoolName = school.name,
                    requestedRole = it.role.name,
                    status = it.verificationStatus.name,
                )
            }
    }

    @Transactional(readOnly = true)
    fun attendanceOverview(current: CurrentUser, schoolIdRaw: String): SchoolAttendanceOverviewPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val studentIds = classes.flatMap { membershipRepository.findAllByClassId(it.id).map { m -> m.studentId } }.distinct()
        val records = if (studentIds.isEmpty()) {
            emptyList()
        } else {
            attendanceRepository.findAllByStudentIdInAndAttendanceDateBetween(studentIds, termStart(), LocalDate.now(zone()))
        }.filter { it.status != AttendanceStatus.EXCUSED }
        val byClass = records.groupBy { it.classId }
        val summaries = classes.map { clazz ->
            val rows = byClass[clazz.id].orEmpty()
            val attended = rows.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
            val chronic = rows.groupBy { it.studentId }.count { (_, studentRows) ->
                studentRows.isNotEmpty() && studentRows.count { it.status == AttendanceStatus.ABSENT } * 1.0 / studentRows.size >= CHRONIC_ABSENCE_RATIO
            }
            ClassAttendanceSummaryPayload(
                classId = clazz.id.toString(),
                className = clazz.name,
                grade = gradeNumber(clazz.gradeLevel),
                attendanceRate = if (rows.isEmpty()) 0.0 else round1(attended * 100.0 / rows.size),
                totalStudents = membershipRepository.countByClassId(clazz.id).toInt(),
                chronicAbsentees = chronic,
            )
        }
        val attended = records.count { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
        return SchoolAttendanceOverviewPayload(
            schoolId = schoolId.toString(),
            overallAttendanceRate = if (records.isEmpty()) 0.0 else round1(attended * 100.0 / records.size),
            chronicAbsenteeCount = summaries.sumOf { it.chronicAbsentees },
            classes = summaries,
        )
    }

    @Transactional(readOnly = true)
    fun systemSettings(current: CurrentUser, schoolIdRaw: String): SystemSettingsPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        return settingsPayload(schoolId, settingsRepository.findById(schoolId).orElse(null))
    }

    @Transactional
    fun updateSystemSettings(current: CurrentUser, schoolIdRaw: String, request: SystemSettingsPayload): SystemSettingsPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = settingsRepository.findById(schoolId).orElse(null)
            ?: SchoolSystemSettingsEntity().apply { this.schoolId = schoolId }
        entity.maintenanceMode = request.maintenanceMode
        entity.registrationOpen = request.registrationOpen
        entity.updatedAt = clock.instant()
        settingsRepository.saveAndFlush(entity)
        auditLogService.record(
            schoolId,
            actor,
            "Updated system settings (maintenance=" + request.maintenanceMode + ", registration=" + request.registrationOpen + ")",
        )
        return settingsPayload(schoolId, entity)
    }

    @Transactional(readOnly = true)
    fun auditLogs(current: CurrentUser, schoolIdRaw: String, limit: Int?): List<AuditLogEntryPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        return auditLogService.list(schoolId, limit ?: DEFAULT_AUDIT_LIMIT)
    }

    @Transactional
    fun backup(current: CurrentUser, schoolIdRaw: String): BackupResultPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val school = schoolRepository.findById(schoolId).orElseThrow { notFound("School not found") }
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val users = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val snapshot = linkedMapOf<String, Any?>(
            "schoolId" to school.id.toString(),
            "schoolName" to school.name,
            "generatedAt" to clock.millis(),
            "classes" to classes.map {
                mapOf("id" to it.id.toString(), "name" to it.name, "gradeLevel" to it.gradeLevel, "subject" to it.subject)
            },
            // Names and role only; credentials are never exported.
            "users" to users.map {
                mapOf("id" to it.id.toString(), "name" to it.name, "role" to it.role.name, "gradeLevel" to it.gradeLevel)
            },
            "config" to configRepository.findById(schoolId).orElse(null)?.let {
                mapOf("schoolName" to it.schoolName, "motto" to it.motto, "primaryColor" to it.primaryColor)
            },
            "settings" to settingsRepository.findById(schoolId).orElse(null)?.let {
                mapOf("maintenanceMode" to it.maintenanceMode, "registrationOpen" to it.registrationOpen)
            },
        )
        val json = mapper.writeValueAsString(snapshot)
        val entity = SchoolBackupEntity().apply {
            this.schoolId = schoolId
            requestedBy = actor.id
            status = "READY"
            sizeBytes = json.toByteArray(Charsets.UTF_8).size.toLong()
            payload = json
        }
        backupRepository.saveAndFlush(entity)
        auditLogService.record(schoolId, actor, "Requested backup (" + entity.sizeBytes + " bytes)")
        return BackupResultPayload(
            success = true,
            message = "Backup created (" + entity.sizeBytes + " bytes)",
            backupId = entity.id.toString(),
            createdAt = entity.createdAt.toEpochMilli(),
            sizeBytes = entity.sizeBytes,
        )
    }

    // ------------------------------------------------------------ internals

    private fun requireSchoolId(raw: String): UUID {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        if (schoolRepository.findById(id).isEmpty) throw notFound("School not found")
        return id
    }

    private fun settingsPayload(schoolId: UUID, entity: SchoolSystemSettingsEntity?) = SystemSettingsPayload(
        schoolId = schoolId.toString(),
        maintenanceMode = entity?.maintenanceMode ?: false,
        registrationOpen = entity?.registrationOpen ?: true,
    )

    private fun zone(): ZoneId = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneId.of("Africa/Nairobi"))

    private fun termLabel(): String = when (LocalDate.now(zone()).monthValue) {
        in 1..4 -> "Term 1"
        in 5..8 -> "Term 2"
        else -> "Term 3"
    }

    private fun termStart(): LocalDate {
        val today = LocalDate.now(zone())
        return when (today.monthValue) {
            in 1..4 -> LocalDate.of(today.year, 1, 1)
            in 5..8 -> LocalDate.of(today.year, 5, 1)
            else -> LocalDate.of(today.year, 9, 1)
        }
    }

    private fun gradeNumber(raw: String?): Int = Regex("\\d+").find(raw ?: "")?.value?.toIntOrNull() ?: 0

    private fun bandOf(percentage: Int): String = when {
        percentage >= 80 -> "EE"
        percentage >= 65 -> "ME"
        percentage >= 50 -> "AE"
        else -> "BE"
    }

    private fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private companion object {
        const val PASS_MARK = 50
        const val CHRONIC_ABSENCE_RATIO = 0.2
        const val DEFAULT_AUDIT_LIMIT = 100
    }
}
