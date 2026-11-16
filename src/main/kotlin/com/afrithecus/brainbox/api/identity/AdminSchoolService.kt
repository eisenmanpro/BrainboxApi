package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.announcement.SchoolAnnouncementService
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.gradebook.repository.GradebookEntryRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolBackupEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolPerformanceSnapshotEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolBackupRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolPerformanceSnapshotRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.AuditLogEntryPayload
import com.afrithecus.brainbox.api.identity.web.BackupDownload
import com.afrithecus.brainbox.api.identity.web.BackupResultPayload
import com.afrithecus.brainbox.api.identity.web.BackupSummaryPayload
import com.afrithecus.brainbox.api.identity.web.AdminStudentPayload
import com.afrithecus.brainbox.api.identity.web.ManagedTeacherPayload
import com.afrithecus.brainbox.api.identity.web.PendingTeacherPayload
import com.afrithecus.brainbox.api.identity.web.ClassAttendanceSummaryPayload
import com.afrithecus.brainbox.api.identity.web.ClassRankingPayload
import com.afrithecus.brainbox.api.identity.web.CoordinatorAssignmentPayload
import com.afrithecus.brainbox.api.identity.web.CoordinatorCandidatePayload
import com.afrithecus.brainbox.api.identity.web.GradeConfigSummaryPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAnalyticsPayload
import com.afrithecus.brainbox.api.identity.web.SchoolAttendanceOverviewPayload
import com.afrithecus.brainbox.api.identity.web.SubjectSchoolPerformancePayload
import com.afrithecus.brainbox.api.identity.web.SystemSettingsPayload
import com.afrithecus.brainbox.api.identity.web.TeacherPerformancePayload
import com.afrithecus.brainbox.api.identity.web.UserApprovalRequestPayload
import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import com.afrithecus.brainbox.api.teacher.repository.TeacherSettingsRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
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
    private val configService: SchoolConfigService,
    private val announcementService: SchoolAnnouncementService,
    private val backupRepository: SchoolBackupRepository,
    private val snapshotRepository: SchoolPerformanceSnapshotRepository,
    private val teacherSettingsRepository: TeacherSettingsRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val questionCodec: QuestionCodec,
    private val auditLogService: AuditLogService,
    private val access: AdminSchoolAccess,
    private val signInPolicy: AdminSignInPolicyService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
) {

    @Transactional
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
        val term = termLabel()
        val year = LocalDate.now(zone()).year
        val overall = if (percentages.isEmpty()) 0.0 else round1(percentages.average())
        // A real trend needs a prior-term baseline; we keep one snapshot per term.
        val previous = previousSnapshot(schoolId, term, year)
        val previousAverages = parseClassAverages(previous?.classAverages)
        val ranked = classRankings.map { row ->
            row.copy(trend = previousAverages[row.classId]?.let { round1(row.average - it) })
        }
        persistSnapshot(schoolId, term, year, overall, ranked)
        return SchoolAnalyticsPayload(
            schoolId = schoolId.toString(),
            schoolName = school.name,
            term = term,
            year = year,
            overallPerformance = overall,
            previousOverallPerformance = previous?.overallPerformance,
            classRankings = ranked,
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
    fun backups(current: CurrentUser, schoolIdRaw: String): List<BackupSummaryPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        return backupRepository.findAllBySchoolIdOrderByCreatedAtDesc(schoolId).map {
            BackupSummaryPayload(
                backupId = it.id.toString(),
                fileName = it.fileName ?: ("backup-" + it.id + ".json"),
                sizeBytes = it.sizeBytes,
                sha256 = it.sha256,
                status = it.status,
                createdAt = it.createdAt.toEpochMilli(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun downloadBackup(current: CurrentUser, schoolIdRaw: String, backupIdRaw: String): BackupDownload {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val id = runCatching { UUID.fromString(backupIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("backupId is not a valid identifier")
        val entity = backupRepository.findById(id).orElse(null) ?: throw notFound("Backup not found")
        if (entity.schoolId != schoolId) throw notFound("Backup not found")
        val payload = entity.payload ?: throw notFound("Backup payload is not available")
        return BackupDownload(entity.fileName ?: ("backup-" + entity.id + ".json"), payload.toByteArray(Charsets.UTF_8))
    }

    @Transactional(readOnly = true)
    fun grades(current: CurrentUser, schoolIdRaw: String): List<GradeConfigSummaryPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        return gradeConfigsFor(schoolId)
    }

    /**
     * Teachers the ICT admin may assign as a grade coordinator: active, approved class teachers
     * (CTEACHER or GRADE_COORDINATOR) of this school. A coordinator carries the grade they
     * already coordinate and the class they lead so the admin can see what a move would replace.
     */
    @Transactional(readOnly = true)
    fun coordinatorCandidates(current: CurrentUser, schoolIdRaw: String): List<CoordinatorCandidatePayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        return userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.role == Role.TEACHER }
            .filter { it.isActive }
            .filter { it.verificationStatus == AccountStatus.VERIFIED }
            // Findings round: the picker must offer every eligible class teacher, not just the
            // current coordinator, so a change/elevation is one pick. The server invariant keeps
            // coordinators class teachers (plain TEACHER/ICT_ADMIN accounts are not eligible).
            .filter { it.subRole == SubRole.CTEACHER || it.subRole == SubRole.GRADE_COORDINATOR }
            .map { teacher ->
                CoordinatorCandidatePayload(
                    userId = teacher.id.toString(),
                    name = teacher.name,
                    phoneNumber = teacher.phoneNumber,
                    gradeLevel = if (teacher.subRole == SubRole.GRADE_COORDINATOR) {
                        teacher.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
                    } else {
                        null
                    },
                    classTeacherOf = classes.firstOrNull { it.teacherUserId == teacher.id }?.name,
                    subRole = teacher.subRole?.name,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Assigns the single grade coordinator for [gradeLevelRaw], or removes the assignment when the
     * body has no teacherId. The school row is locked first so two concurrent assignments cannot
     * leave one grade with two coordinators (the same serialisation [CoordinatorGradeGuard] uses).
     * Replacing a holder demotes them to CTEACHER and keeps their class/grade rows.
     */
    @Transactional
    fun assignCoordinator(
        current: CurrentUser,
        schoolIdRaw: String,
        gradeLevelRaw: String,
        request: CoordinatorAssignmentPayload?,
    ): GradeConfigSummaryPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        schoolRepository.findByIdForUpdate(schoolId) ?: throw notFound("School not found")
        val grade = canonicalGrade(schoolId, gradeLevelRaw)
        val teacherIdRaw = request?.teacherId?.trim()?.takeIf { it.isNotEmpty() }
        if (teacherIdRaw == null) {
            removeCoordinator(schoolId, actor, grade)
        } else {
            assignCoordinatorTo(schoolId, actor, grade, teacherIdRaw)
        }
        return gradeConfigsFor(schoolId).firstOrNull { sameGrade(it.gradeLevel, grade) }
            ?: GradeConfigSummaryPayload(
                configId = configIdFor(grade),
                gradeLevel = grade,
                displayName = grade,
            )
    }

    @Transactional(readOnly = true)
    fun approvals(current: CurrentUser, schoolIdRaw: String): List<UserApprovalRequestPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        return approvalsFor(schoolId)
    }

    // ------------------------------------------------ teacher management lists

    /**
     * Teachers waiting for this ICT admin's approval. The web's Pending tab renders these
     * (`AdminApprovalsRepository.getPendingTeachers`), and `approveTeacher`/
     * `rejectTeacher` act on the same rows by id.
     */
    @Transactional(readOnly = true)
    fun pendingTeachers(current: CurrentUser, schoolIdRaw: String): List<PendingTeacherPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val rows = teacherRows(schoolId, AccountStatus.PENDING_VERIFICATION)
        val details = detailsFor(rows)
        return rows.map { row ->
            val pair = details.getValue(row.id)
            pendingPayload(row, pair.settings, pair.derived, pair.teacherCode)
        }
    }

    /** Teachers the school rejected; a mistaken rejection is re-approvable from this list. */
    @Transactional(readOnly = true)
    fun rejectedTeachers(current: CurrentUser, schoolIdRaw: String): List<PendingTeacherPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val rows = teacherRows(schoolId, AccountStatus.REJECTED)
        val details = detailsFor(rows)
        return rows.map { row ->
            val pair = details.getValue(row.id)
            pendingPayload(row, pair.settings, pair.derived, pair.teacherCode)
        }
    }

    /**
     * The manageable roster: approved teachers plus frozen accounts. A FROZEN account is
     * inactive, so this list deliberately does not use the active-only query.
     */
    @Transactional(readOnly = true)
    fun managedTeachers(current: CurrentUser, schoolIdRaw: String): List<ManagedTeacherPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        // The caller's own account is not something they manage; freezing/transferring
        // yourself is never a valid action, so it is not offered in the list.
        val rows = teacherRows(schoolId, AccountStatus.VERIFIED, AccountStatus.FROZEN)
            .filter { it.id != current.userId }
        val details = detailsFor(rows)
        return rows.map { row ->
            val pair = details.getValue(row.id)
            managedPayload(row, pair.settings, pair.derived)
        }
    }

    /**
     * The school's learners for the admin audience picker (announcement targeting).
     * Filters are optional and combine with AND: `gradeLevel` matches the learner's own
     * grade or their class grade, `classId` a current class membership, `q` a
     * case-insensitive name/phone/admission search. The guardian contact is the same
     * account F9 keeps for the learner (linked parent row, else the guardian fields).
     * Roster-only records are provisioned for reports only and cannot receive anything,
     * so they are excluded.
     */
    @Transactional(readOnly = true)
    fun students(
        current: CurrentUser,
        schoolIdRaw: String,
        gradeLevel: String?,
        classId: String?,
        query: String?,
    ): List<AdminStudentPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        val learners = userRepository.findAllBySchoolIdAndRoleOrderByNameAsc(schoolId, Role.STUDENT)
            .filter { it.accountKind == AccountKind.FULL }
        if (learners.isEmpty()) return emptyList()
        val schoolClasses = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val memberships = if (schoolClasses.isEmpty()) {
            emptyList()
        } else {
            membershipRepository.findAllByClassIdIn(schoolClasses.map { it.id })
        }
        val classById = schoolClasses.associateBy { it.id }
        val classesByStudent = memberships.groupBy { it.studentId }
        val askedClass = classId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw invalidArgument("classId is not a valid identifier") }
        val askedGrade = gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
        val needle = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val parentIds = learners.mapNotNull { it.parentUserId }.distinct()
        val parents = if (parentIds.isEmpty()) emptyMap() else userRepository.findAllById(parentIds).associateBy { it.id }
        return learners.mapNotNull { learner ->
            val rows = classesByStudent[learner.id].orEmpty()
            val classRows = rows.mapNotNull { classById[it.classId] }
            val grades = (classRows.map { it.gradeLevel } + listOfNotNull(learner.gradeLevel))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (askedClass != null && rows.none { it.classId == askedClass }) return@mapNotNull null
            if (askedGrade != null && grades.none { it.equals(askedGrade, ignoreCase = true) }) return@mapNotNull null
            if (needle != null) {
                val haystack = listOfNotNull(learner.name, learner.phoneNumber, learner.studentAdmissionNumber)
                    .joinToString(" ").lowercase()
                if (!haystack.contains(needle)) return@mapNotNull null
            }
            val parent = learner.parentUserId?.let { parents[it] }
            AdminStudentPayload(
                id = learner.id.toString(),
                name = learner.name,
                gradeLevel = learner.gradeLevel ?: classRows.firstOrNull()?.gradeLevel,
                classIds = rows.map { it.classId.toString() },
                classNames = classRows.map { it.name },
                parentName = parent?.name ?: learner.guardianName,
                parentPhone = parent?.phoneNumber ?: learner.guardianPhone,
            )
        }
    }

    private fun teacherRows(schoolId: UUID, vararg statuses: AccountStatus): List<UserEntity> {
        val wanted = statuses.toSet()
        return userRepository.findAllBySchoolIdAndRoleOrderByNameAsc(schoolId, Role.TEACHER)
            .filter { it.verificationStatus in wanted }
            .sortedBy { it.name.lowercase() }
    }

    private fun pendingPayload(
        user: UserEntity,
        settings: TeacherSettingsEntity?,
        derived: DerivedTeacherDetails,
        teacherCode: String?,
    ): PendingTeacherPayload {
        return PendingTeacherPayload(
            id = user.id.toString(),
            name = user.name,
            phoneNumber = user.phoneNumber.orEmpty(),
            teacherCode = teacherCode,
            className = derived.className,
            gradeLevels = derived.gradeLevels,
            subjects = derived.subjects,
            tscNumber = settings?.tscNumber,
            joinedAt = user.createdAt.toEpochMilli(),
        )
    }

    private fun managedPayload(
        user: UserEntity,
        settings: TeacherSettingsEntity?,
        derived: DerivedTeacherDetails,
    ): ManagedTeacherPayload {
        return ManagedTeacherPayload(
            id = user.id.toString(),
            name = user.name,
            phoneNumber = user.phoneNumber.orEmpty(),
            role = user.role.name,
            subRole = user.subRole?.name,
            className = derived.className,
            gradeLevels = derived.gradeLevels,
            subjects = derived.subjects,
            tscNumber = settings?.tscNumber,
            isFrozen = user.verificationStatus == AccountStatus.FROZEN,
            verificationStatus = user.verificationStatus.name,
        )
    }

    /** Batch-reads the per-teacher settings and owned classes for one list, keyed by user id. */
    private fun detailsFor(rows: List<UserEntity>): Map<UUID, TeacherPair> {
        if (rows.isEmpty()) return emptyMap()
        val ids = rows.map { it.id }
        val settings = teacherSettingsRepository.findAllByTeacherIdIn(ids).associateBy { it.teacherId }
        val classes = classRepository.findAllByTeacherUserIdInAndIsActiveTrueOrderByNameAsc(ids)
            .groupBy { it.teacherUserId }
        val codes = teacherCodeRepository.findAllByTeacherUserIdInAndActiveTrue(ids).associateBy { it.teacherUserId }
        return rows.associate { row ->
            val entity = settings[row.id]
            row.id to TeacherPair(entity, DerivedTeacherDetails(entity, classes[row.id].orEmpty()), codes[row.id]?.code)
        }
    }

    private class TeacherPair(
        val settings: TeacherSettingsEntity?,
        val derived: DerivedTeacherDetails,
        val teacherCode: String?,
    )

    /**
     * N3 rule shared with the teacher profile read: the admin-approved catalogue
     * (`primary_class_name` / `grades_taught` / `subjects_taught`) wins when present;
     * otherwise fall back to the classes the teacher actually owns. An empty list is a
     * real "nothing assigned", never an invented value.
     */
    private inner class DerivedTeacherDetails(
        settings: TeacherSettingsEntity?,
        classes: List<TeacherClassEntity>,
    ) {
        val className: String? = settings?.primaryClassName?.takeIf { it.isNotBlank() }
            ?: classes.firstOrNull()?.name
        val gradeLevels: List<String> = questionCodec.parseList(settings?.gradesTaught).orEmpty()
            .ifEmpty { classes.map { it.gradeLevel }.distinct() }
        val subjects: List<String> = questionCodec.parseList(settings?.subjectsTaught).orEmpty()
            .ifEmpty { classes.map { it.subject }.distinct() }
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
        val actor = access.require(current, schoolId)
        // A read must not write the pin; the owner is still resolved so the client can
        // disable a control that would 403 (isAdminOwner).
        val ownerId = signInPolicy.ownerOf(schoolId, persist = false)
        return settingsPayload(schoolId, settingsRepository.findById(schoolId).orElse(null), actor, ownerId)
    }

    @Transactional
    fun updateSystemSettings(current: CurrentUser, schoolIdRaw: String, request: SystemSettingsPayload): SystemSettingsPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val entity = settingsRepository.findById(schoolId).orElse(null)
            ?: SchoolSystemSettingsEntity().apply { this.schoolId = schoolId }
        val wasOpen = entity.adminSignInOpen
        val ownerId = signInPolicy.ownerOf(schoolId, persist = true, settingsOverride = entity)
        // N4: any administrator of the school may close the door; only the pinned first
        // administrator (or a platform operator as audited break-glass) may open it again.
        if (!wasOpen && request.adminSignInOpen) {
            val mayUnlock = actor.id == ownerId || actor.role == Role.ADMIN
            if (!mayUnlock) {
                throw ApiException(
                    ApiErrorCode.FORBIDDEN,
                    "Only the school's first administrator can turn administrator sign-in back on.",
                    mapOf("reason" to "ADMIN_SIGNIN_OWNER_ONLY"),
                )
            }
        }
        entity.maintenanceMode = request.maintenanceMode
        entity.registrationOpen = request.registrationOpen
        entity.transitionsEnabled = request.transitionsEnabled
        entity.adminSignInOpen = request.adminSignInOpen
        entity.updatedAt = clock.instant()
        settingsRepository.saveAndFlush(entity)
        val closedNow = wasOpen && !request.adminSignInOpen
        val revoked = if (closedNow) signInPolicy.revokeOtherAdministratorSessions(schoolId, ownerId) else 0
        auditLogService.record(
            schoolId,
            actor,
            "Updated system settings (maintenance=" + request.maintenanceMode +
                ", registration=" + request.registrationOpen +
                ", transitions=" + request.transitionsEnabled +
                ", adminSignInOpen=" + request.adminSignInOpen +
                (if (closedNow) ", signed out " + revoked + " administrator session(s)" else "") + ")",
        )
        return settingsPayload(schoolId, entity, actor, ownerId)
    }

    @Transactional(readOnly = true)
    fun auditLogs(current: CurrentUser, schoolIdRaw: String, limit: Int?, before: Long?): List<AuditLogEntryPayload> {
        val schoolId = requireSchoolId(schoolIdRaw)
        access.require(current, schoolId)
        return auditLogService.list(
            schoolId,
            limit ?: DEFAULT_AUDIT_LIMIT,
            before?.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        )
    }

    @Transactional
    fun backup(current: CurrentUser, schoolIdRaw: String): BackupResultPayload {
        val schoolId = requireSchoolId(schoolIdRaw)
        val actor = access.require(current, schoolId)
        val school = schoolRepository.findById(schoolId).orElseThrow { notFound("School not found") }
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val users = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val snapshot = linkedMapOf<String, Any?>(
            "schemaVersion" to BACKUP_SCHEMA_VERSION,
            "schoolId" to school.id.toString(),
            "schoolName" to school.name,
            "generatedAt" to clock.millis(),
            "config" to configService.branding(schoolId),
            "settings" to settingsPayload(schoolId, settingsRepository.findById(schoolId).orElse(null)),
            "gradeConfigs" to gradeConfigsFor(schoolId),
            "announcements" to announcementService.list(schoolId.toString()),
            "userApprovals" to approvalsFor(schoolId),
            "classes" to classes.map {
                mapOf("id" to it.id.toString(), "name" to it.name, "gradeLevel" to it.gradeLevel, "subject" to it.subject)
            },
            // Names and role only; credentials are never exported.
            "users" to users.map {
                mapOf("id" to it.id.toString(), "name" to it.name, "role" to it.role.name, "gradeLevel" to it.gradeLevel)
            },
        )
        val json = mapper.writeValueAsString(snapshot)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val fileName = "brainbox-backup-" + schoolId + "-" + clock.millis() + ".json"
        val entity = SchoolBackupEntity().apply {
            this.schoolId = schoolId
            requestedBy = actor.id
            status = "READY"
            sizeBytes = bytes.size.toLong()
            payload = json
            this.fileName = fileName
            this.sha256 = sha256
        }
        backupRepository.saveAndFlush(entity)
        auditLogService.record(schoolId, actor, "Requested backup (" + entity.sizeBytes + " bytes)")
        return BackupResultPayload(
            success = true,
            message = "Backup created (" + entity.sizeBytes + " bytes)",
            backupId = entity.id.toString(),
            createdAt = entity.createdAt.toEpochMilli(),
            sizeBytes = entity.sizeBytes,
            fileName = fileName,
            sha256 = sha256,
        )
    }

    // ------------------------------------------------------------ internals

    /** Grade config rows shared by the grades endpoint and the backup export. */
    private fun gradeConfigsFor(schoolId: UUID): List<GradeConfigSummaryPayload> {
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val coordinators = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.role == Role.TEACHER && it.subRole == SubRole.GRADE_COORDINATOR }
        return classes.groupBy { it.gradeLevel }.map { (grade, rows) ->
            val classTeacher = rows.firstOrNull()?.teacherUserId?.let { userRepository.findById(it).orElse(null)?.name }
            val coordinator = coordinators.firstOrNull { sameGrade(it.gradeLevel, grade) }
            GradeConfigSummaryPayload(
                configId = configIdFor(grade),
                gradeLevel = grade,
                displayName = grade,
                classTeacherName = classTeacher,
                coordinatorName = coordinator?.name,
                coordinatorId = coordinator?.id?.toString(),
                active = rows.any { it.isActive },
            )
        }.sortedBy { it.gradeLevel }
    }

    // ------------------------------------------------------------ grade coordinators

    /** The catalogue spelling of [gradeLevelRaw]; the grade must exist as an active class row. */
    private fun canonicalGrade(schoolId: UUID, gradeLevelRaw: String): String {
        val wanted = gradeLevelRaw.trim()
        if (wanted.isEmpty()) throw invalidArgument("gradeLevel is required")
        return classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .firstOrNull { sameGrade(it.gradeLevel, wanted) }
            ?.gradeLevel
            ?: throw notFound("Grade not found")
    }

    private fun activeCoordinators(schoolId: UUID): List<UserEntity> =
        userRepository.findAllBySchoolIdAndSubRoleAndIsActiveTrue(schoolId, SubRole.GRADE_COORDINATOR)

    private fun removeCoordinator(schoolId: UUID, actor: UserEntity, grade: String) {
        val holder = activeCoordinators(schoolId).firstOrNull { sameGrade(it.gradeLevel, grade) } ?: return
        // Keep the class and grade rows: they stay a class teacher, they only lose the grade lead.
        holder.subRole = SubRole.CTEACHER
        userRepository.saveAndFlush(holder)
        auditLogService.record(schoolId, actor, "Removed coordinator for " + grade + ": " + holder.name)
    }

    private fun assignCoordinatorTo(schoolId: UUID, actor: UserEntity, grade: String, teacherIdRaw: String) {
        val teacherId = runCatching { UUID.fromString(teacherIdRaw) }.getOrNull()
            ?: throw invalidArgument("teacherId is not a valid identifier")
        val teacher = userRepository.findById(teacherId).orElse(null)
        // Another school's staff must look exactly like an unknown id: never leak the tenant.
        if (teacher == null || teacher.schoolId != schoolId || teacher.role != Role.TEACHER) {
            throw notFound("Teacher not found")
        }
        if (!teacher.isActive) throw conflict("This account is not active and cannot coordinate a grade.")
        if (teacher.verificationStatus != AccountStatus.VERIFIED) {
            throw conflict("Only approved staff can be assigned a grade to coordinate.")
        }
        // Server invariant (CoordinatorAssignmentWebTests): only active, approved class
        // teachers coordinate a grade. The findings-round UX still lists ALL of them in the
        // picker and replaces the holder in one step; it never elevates ordinary staff accounts.
        if (teacher.subRole != SubRole.CTEACHER && teacher.subRole != SubRole.GRADE_COORDINATOR) {
            throw conflict("Only class teachers can coordinate a grade.")
        }
        val coordinators = activeCoordinators(schoolId)
        // One grade per coordinator: a teacher who already leads another grade must be moved
        // out of that grade first, so the picker disables that candidate with an explanation.
        if (teacher.subRole == SubRole.GRADE_COORDINATOR) {
            val otherGrade = coordinators.firstOrNull { it.id == teacher.id }
                ?.gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
            if (otherGrade != null && !sameGrade(otherGrade, grade)) {
                throw conflict("This teacher already coordinates " + otherGrade + ". Move that assignment first.")
            }
        }
        val currentHolder = coordinators.firstOrNull { sameGrade(it.gradeLevel, grade) }
        if (currentHolder?.id == teacher.id) return // already this grade's coordinator: idempotent
        if (currentHolder != null) {
            // The previous holder returns to their class-teacher duties and keeps their class.
            currentHolder.subRole = SubRole.CTEACHER
            userRepository.saveAndFlush(currentHolder)
        }
        // Re-read after the demotion: under the school lock the grade must now be free.
        val stillTaken = activeCoordinators(schoolId).firstOrNull { sameGrade(it.gradeLevel, grade) && it.id != teacher.id }
        if (stillTaken != null) {
            throw conflict(grade + " already has a grade coordinator at this school. Move that assignment before adding another.")
        }
        teacher.subRole = SubRole.GRADE_COORDINATOR
        teacher.gradeLevel = grade
        userRepository.saveAndFlush(teacher)
        auditLogService.record(schoolId, actor, "Assigned coordinator for " + grade + ": " + teacher.name)
    }

    private fun sameGrade(a: String?, b: String?): Boolean =
        a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)

    private fun configIdFor(grade: String): String = "grade_" + grade.lowercase().replace(Regex("[^a-z0-9]+"), "_")

    /** Pending user approvals shared by the approvals endpoint and the backup export. */
    private fun approvalsFor(schoolId: UUID): List<UserApprovalRequestPayload> {
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
                    phoneVerified = it.phoneVerifiedAt != null,
                )
            }
    }

    private fun previousSnapshot(schoolId: UUID, term: String, year: Int): SchoolPerformanceSnapshotEntity? =
        snapshotRepository.findAllBySchoolIdOrderBySnapshotYearDescTermDesc(schoolId)
            .firstOrNull { it.term != term || it.snapshotYear != year }

    private fun persistSnapshot(
        schoolId: UUID,
        term: String,
        year: Int,
        overall: Double,
        classRankings: List<ClassRankingPayload>,
    ) {
        val entity = snapshotRepository.findBySchoolIdAndTermAndSnapshotYear(schoolId, term, year)
            ?: SchoolPerformanceSnapshotEntity().apply {
                this.schoolId = schoolId
                this.term = term
                snapshotYear = year
            }
        entity.overallPerformance = overall
        entity.classAverages = mapper.writeValueAsString(classRankings.associate { it.classId to it.average })
        entity.generatedAt = clock.instant()
        snapshotRepository.saveAndFlush(entity)
    }

    private fun parseClassAverages(json: String?): Map<String, Double> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (entry in node.properties()) out[entry.key] = entry.value.asDouble()
        return out
    }

    private fun requireSchoolId(raw: String): UUID {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        if (schoolRepository.findById(id).isEmpty) throw notFound("School not found")
        return id
    }

    private fun settingsPayload(
        schoolId: UUID,
        entity: SchoolSystemSettingsEntity?,
        actor: UserEntity? = null,
        ownerId: UUID? = null,
    ) = SystemSettingsPayload(
        schoolId = schoolId.toString(),
        maintenanceMode = entity?.maintenanceMode ?: false,
        registrationOpen = entity?.registrationOpen ?: true,
        transitionsEnabled = entity?.transitionsEnabled ?: true,
        adminSignInOpen = entity?.adminSignInOpen ?: true,
        adminOwnerId = (entity?.adminOwnerId ?: ownerId)?.toString(),
        isAdminOwner = actor != null && ownerId != null && actor.id == ownerId,
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
        const val BACKUP_SCHEMA_VERSION = 2
    }
}
