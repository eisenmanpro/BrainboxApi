package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.classes.ClassTransitionService
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.TransitionRequest
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.LearnerClassOptionPayload
import com.afrithecus.brainbox.api.identity.web.LearnerCountsPayload
import com.afrithecus.brainbox.api.identity.web.LearnerDetailPayload
import com.afrithecus.brainbox.api.identity.web.LearnerFilterOptionsPayload
import com.afrithecus.brainbox.api.identity.web.LearnerPagePayload
import com.afrithecus.brainbox.api.identity.web.LearnerSummaryPayload
import com.afrithecus.brainbox.api.identity.web.LearnerTransferRequest
import com.afrithecus.brainbox.api.identity.web.LearnerTransferResultPayload
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * School-scoped learner management for the ICT admin console (findings "user
 * management": the school admin could not see, filter, export or transfer the
 * school's learners).
 *
 * Tenant rule: the school always comes from the path and is checked against the
 * actor's token via [AdminSchoolAccess] — an ICT admin works on their own school
 * only, a platform ADMIN on any school. A learner is only ever visible inside
 * their own school, so another tenant's learner answers 404 rather than leaking
 * that they exist somewhere else.
 *
 * Transfers: a class move inside the school reuses the audited
 * [ClassTransitionService] pipeline (single class membership + transition row +
 * notifications). A cross-school transfer is a platform-admin action because it
 * writes into another tenant's roster; an ICT admin asking for one gets an
 * actionable 403 instead of a silent no-op.
 */
@Service
class AdminLearnerService(
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val access: AdminSchoolAccess,
    private val transitionService: ClassTransitionService,
    private val auditLogService: AuditLogService,
    private val notifications: NotificationService,
) {

    @Transactional(readOnly = true)
    fun page(
        current: CurrentUser,
        schoolIdRaw: String,
        q: String?,
        gradeLevel: String?,
        classId: String?,
        status: String?,
        page: Int,
        size: Int,
    ): LearnerPagePayload {
        val schoolId = requireSchool(schoolIdRaw).id
        access.require(current, schoolId)
        val ctx = context(schoolId)
        val base = filterRows(ctx, q, gradeLevel, classId)
        val statusFilter = normaliseStatus(status)
        val filtered = if (statusFilter == null) base else base.filter { matchesStatus(it, statusFilter) }
        val pageSize = size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val pageIndex = page.coerceAtLeast(0)
        val total = filtered.size
        val items = filtered.drop(pageIndex * pageSize).take(pageSize).map { summary(ctx, it) }
        return LearnerPagePayload(
            items = items,
            page = pageIndex,
            size = pageSize,
            total = total,
            totalPages = if (total == 0) 0 else (total + pageSize - 1) / pageSize,
            counts = LearnerCountsPayload(
                total = base.size,
                active = base.count { it.isActive },
                inactive = base.count { !it.isActive },
            ),
            filters = filterOptions(ctx),
        )
    }

    @Transactional(readOnly = true)
    fun detail(current: CurrentUser, schoolIdRaw: String, studentIdRaw: String): LearnerDetailPayload {
        val schoolId = requireSchool(schoolIdRaw).id
        access.require(current, schoolId)
        val ctx = context(schoolId)
        val student = requireLearner(ctx, studentIdRaw)
        val parent = student.parentUserId?.let { userRepository.findById(it).orElse(null) }
        val classes = classesOf(ctx, student)
        return LearnerDetailPayload(
            studentId = student.id.toString(),
            name = student.name,
            admissionNumber = student.studentAdmissionNumber?.takeIf { it.isNotBlank() },
            gradeLevel = gradeOf(student, classes),
            classIds = classes.map { it.id.toString() },
            classNames = classes.map { it.name },
            guardianName = student.guardianName?.takeIf { it.isNotBlank() },
            guardianPhone = student.guardianPhone?.takeIf { it.isNotBlank() },
            parentUserId = parent?.id?.toString(),
            parentName = parent?.name?.takeIf { it.isNotBlank() },
            parentPhone = parent?.phoneNumber?.takeIf { it.isNotBlank() },
            phoneNumber = student.phoneNumber?.takeIf { it.isNotBlank() },
            email = student.email?.takeIf { it.isNotBlank() },
            status = student.verificationStatus.name,
            isActive = student.isActive,
            accountKind = student.accountKind.name,
            joinedAt = student.createdAt.toEpochMilli(),
            lastLoginAt = student.lastLogin?.toEpochMilli(),
        )
    }

    @Transactional
    fun transfer(current: CurrentUser, schoolIdRaw: String, studentIdRaw: String, request: LearnerTransferRequest): LearnerTransferResultPayload {
        val schoolId = requireSchool(schoolIdRaw).id
        val actor = access.require(current, schoolId)
        val ctx = context(schoolId)
        val student = requireLearner(ctx, studentIdRaw)
        val reason = request.reason?.trim().orEmpty()
        if (reason.length < MIN_REASON) throw invalidArgument("Give a reason (at least " + MIN_REASON + " characters)")
        val toClassId = request.toClassId?.trim()?.takeIf { it.isNotEmpty() }
        val targetSchoolIdRaw = request.targetSchoolId?.trim()?.takeIf { it.isNotEmpty() }
        if ((toClassId == null) == (targetSchoolIdRaw == null)) {
            throw invalidArgument("Choose exactly one destination: a class in this school or another school")
        }

        if (toClassId != null) {
            return moveClass(actor, schoolId, student, toClassId, reason)
        }

        // Cross-school transfer writes into another tenant's roster, so it stays a
        // platform-admin action. The ICT admin gets a next action, not a bare 403.
        if (actor.role != Role.ADMIN) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "Only a Brainbox platform administrator can transfer a learner to another school. Move the learner into a class in your school instead.",
            )
        }
        return transferSchool(actor, schoolId, student, targetSchoolIdRaw!!, reason)
    }

    /** Filtered CSV (the same rows the table shows, all pages). CSV-safe, formula-injection guarded. */
    @Transactional(readOnly = true)
    fun csv(current: CurrentUser, schoolIdRaw: String, q: String?, gradeLevel: String?, classId: String?, status: String?): String {
        val schoolId = requireSchool(schoolIdRaw).id
        access.require(current, schoolId)
        val ctx = context(schoolId)
        val base = filterRows(ctx, q, gradeLevel, classId)
        val statusFilter = normaliseStatus(status)
        val rows = if (statusFilter == null) base else base.filter { matchesStatus(it, statusFilter) }
        val out = StringBuilder()
        out.append("Name,Admission No,Grade,Classes,Guardian Name,Guardian Phone,Learner Phone,Email,Status,Active,Account Kind")
        rows.forEach { student ->
            val classes = classesOf(ctx, student)
            out.append('\n')
            out.append(
                listOf(
                    student.name,
                    student.studentAdmissionNumber.orEmpty(),
                    gradeOf(student, classes).orEmpty(),
                    classes.map { it.name }.joinToString(" | "),
                    student.guardianName.orEmpty(),
                    student.guardianPhone.orEmpty(),
                    student.phoneNumber.orEmpty(),
                    student.email.orEmpty(),
                    student.verificationStatus.name,
                    if (student.isActive) "YES" else "NO",
                    student.accountKind.name,
                ).joinToString(",") { csvCell(it) },
            )
        }
        return out.toString() + "\n"
    }

    fun exportFileName(schoolIdRaw: String): String {
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(java.time.Instant.now())
        val safeSchool = schoolIdRaw.replace(Regex("[^A-Za-z0-9-]"), "").take(40)
        return "learners-" + safeSchool + "-" + day + ".csv"
    }

    // ---------------------------------------------------------------- helpers

    private data class LearnerContext(
        val students: List<UserEntity>,
        val classes: List<TeacherClassEntity>,
        val classById: Map<UUID, TeacherClassEntity>,
        val membershipsByStudent: Map<UUID, List<TeacherClassEntity>>,
        val provisionedClasses: Map<UUID, TeacherClassEntity>,
    )

    private fun requireSchool(schoolIdRaw: String): SchoolEntity {
        val schoolId = runCatching { UUID.fromString(schoolIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("schoolId is not a valid identifier")
        return schoolRepository.findById(schoolId).orElse(null) ?: throw notFound("School not found")
    }

    /** One round of reads: the school's learners, its classes and the class memberships. */
    private fun context(schoolId: UUID): LearnerContext {
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val classById = classes.associateBy { it.id }
        val memberships = if (classes.isEmpty()) emptyList() else membershipRepository.findAllByClassIdIn(classes.map { it.id })
        val byStudent = HashMap<UUID, MutableList<TeacherClassEntity>>()
        memberships.forEach { membership ->
            classById[membership.classId]?.let { clazz ->
                byStudent.getOrPut(membership.studentId) { mutableListOf() }.add(clazz)
            }
        }
        val students = userRepository.findAllBySchoolIdAndRoleOrderByNameAsc(schoolId, Role.STUDENT)
        // Roster-only (provisioned) learners carry provisionedClassId instead of a membership row.
        val provisionedIds = students.mapNotNull { it.provisionedClassId }.distinct()
        val provisionedClasses = if (provisionedIds.isEmpty()) emptyMap() else classRepository.findAllById(provisionedIds).associateBy { it.id }
        return LearnerContext(students, classes, classById, byStudent, provisionedClasses)
    }

    private fun classesOf(ctx: LearnerContext, student: UserEntity): List<TeacherClassEntity> {
        val rows = (ctx.membershipsByStudent[student.id] ?: emptyList()).toMutableList()
        // provisionedClassId is a tagging field for ROSTER_ONLY records, not a membership:
        // a learner approved into a full account keeps the stale id but belongs only to
        // their real class memberships, so exposing it here would invent a class.
        if (student.accountKind == AccountKind.ROSTER_ONLY) {
            student.provisionedClassId?.let { id ->
                ctx.provisionedClasses[id]?.let { clazz -> if (rows.none { it.id == clazz.id }) rows.add(clazz) }
            }
        }
        return rows.sortedBy { it.name.lowercase() }
    }

    private fun gradeOf(student: UserEntity, classes: List<TeacherClassEntity>): String? =
        classes.firstOrNull()?.gradeLevel?.takeIf { it.isNotBlank() }
            ?: student.gradeLevel?.takeIf { it.isNotBlank() }

    private fun filterRows(ctx: LearnerContext, q: String?, gradeLevel: String?, classId: String?): List<UserEntity> {
        val query = q?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val grade = gradeLevel?.trim()?.takeIf { it.isNotEmpty() }
        val classUuid = classId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument("classId is not a valid identifier")
        }
        // The path school's class map is the tenant boundary: an unknown class id is a 404,
        // so a probe cannot tell another school's class id apart from a missing one.
        if (classUuid != null && ctx.classById[classUuid] == null) throw notFound("Class not found")
        val classFilter = classUuid?.let { ctx.classById[it] }
        return ctx.students.filter { student ->
            if (query != null && !matchesQuery(student, query)) return@filter false
            val classes = if (grade != null || classFilter != null) classesOf(ctx, student) else emptyList()
            if (grade != null) {
                val gradeMatch = (classes.any { it.gradeLevel.equals(grade, ignoreCase = true) }) ||
                    student.gradeLevel?.equals(grade, ignoreCase = true) == true
                if (!gradeMatch) return@filter false
            }
            if (classFilter != null) {
                val classMatch = classes.any { it.id == classFilter.id }
                if (!classMatch) return@filter false
            }
            true
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.id.toString() }))
    }

    private fun matchesQuery(student: UserEntity, query: String): Boolean {
        val haystack = listOf(
            student.name,
            student.studentAdmissionNumber,
            student.phoneNumber,
            student.email,
            student.guardianName,
            student.guardianPhone,
        ).joinToString(" ").lowercase()
        return haystack.contains(query)
    }

    private fun normaliseStatus(raw: String?): String? {
        val value = raw?.trim()?.uppercase().orEmpty()
        if (value.isEmpty() || value == "ANY" || value == "ALL") return null
        if (value !in STATUS_FILTERS) {
            throw invalidArgument("status must be one of " + STATUS_FILTERS.joinToString(", "))
        }
        return value
    }

    private fun matchesStatus(student: UserEntity, filter: String): Boolean = when (filter) {
        "ACTIVE" -> student.isActive
        "INACTIVE" -> !student.isActive
        else -> student.verificationStatus.name == filter
    }

    private fun filterOptions(ctx: LearnerContext): LearnerFilterOptionsPayload {
        val grades = linkedSetOf<String>()
        ctx.classes.forEach { clazz -> clazz.gradeLevel.takeIf { it.isNotBlank() }?.let { grades += it } }
        ctx.students.forEach { student -> student.gradeLevel?.takeIf { it.isNotBlank() }?.let { grades += it } }
        return LearnerFilterOptionsPayload(
            grades = grades.sortedWith(compareBy({ gradeNumber(it) }, { it.lowercase() })),
            classes = ctx.classes.map { LearnerClassOptionPayload(it.id.toString(), it.name, it.gradeLevel) },
            statuses = STATUS_FILTERS,
        )
    }

    private fun gradeNumber(grade: String): Int = Regex("[0-9]+").find(grade)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private fun requireLearner(ctx: LearnerContext, studentIdRaw: String): UserEntity {
        val studentId = runCatching { UUID.fromString(studentIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("studentId is not a valid identifier")
        return ctx.students.firstOrNull { it.id == studentId } ?: throw notFound("Learner not found")
    }

    private fun summary(ctx: LearnerContext, student: UserEntity): LearnerSummaryPayload {
        val classes = classesOf(ctx, student)
        return LearnerSummaryPayload(
            studentId = student.id.toString(),
            name = student.name,
            admissionNumber = student.studentAdmissionNumber?.takeIf { it.isNotBlank() },
            gradeLevel = gradeOf(student, classes),
            classIds = classes.map { it.id.toString() },
            classNames = classes.map { it.name },
            guardianName = student.guardianName?.takeIf { it.isNotBlank() },
            guardianPhone = student.guardianPhone?.takeIf { it.isNotBlank() },
            phoneNumber = student.phoneNumber?.takeIf { it.isNotBlank() },
            status = student.verificationStatus.name,
            isActive = student.isActive,
            accountKind = student.accountKind.name,
        )
    }

    private fun moveClass(
        actor: UserEntity,
        schoolId: UUID,
        student: UserEntity,
        toClassIdRaw: String,
        reason: String,
    ): LearnerTransferResultPayload {
        val toClassId = runCatching { UUID.fromString(toClassIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("toClassId is not a valid identifier")
        // A provisioned record is deliberately not a class member: membership feeds attendance,
        // gradebook, homework and messaging, so a roster-only learner cannot be moved into a class.
        if (student.accountKind == AccountKind.ROSTER_ONLY) {
            throw invalidArgument("Roster-only learners cannot join a class. Approve the learner into a full account first.")
        }
        val target = classRepository.findById(toClassId).orElse(null) ?: throw notFound("Target class not found")
        // The path school is the tenant boundary: another school's class is not confirmable.
        if (target.schoolId != schoolId) throw notFound("Target class not found")
        if (!target.isActive) throw invalidArgument("Target class is no longer active")
        val currentClassIds = classesOf(context(schoolId), student).map { it.id.toString() }
        val result = transitionService.transition(
            actor,
            student.id.toString(),
            TransitionRequest(toClassId = target.id.toString(), reason = reason, mode = "PUSH"),
        )
        auditLogService.record(
            schoolId,
            actor,
            "Moved learner " + student.name + " to " + target.name + " (" + target.gradeLevel + "): " + reason,
        )
        return LearnerTransferResultPayload(
            studentId = student.id.toString(),
            kind = "CLASS",
            fromSchoolId = schoolId.toString(),
            toSchoolId = schoolId.toString(),
            fromClassIds = currentClassIds,
            toClassId = target.id.toString(),
            message = result.message,
        )
    }

    private fun transferSchool(
        actor: UserEntity,
        schoolId: UUID,
        student: UserEntity,
        targetSchoolIdRaw: String,
        reason: String,
    ): LearnerTransferResultPayload {
        val targetSchoolId = runCatching { UUID.fromString(targetSchoolIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("targetSchoolId is not a valid identifier")
        if (targetSchoolId == schoolId) throw invalidArgument("Choose a different school")
        val targetSchool = schoolRepository.findById(targetSchoolId).orElse(null) ?: throw notFound("Target school not found")
        if (!targetSchool.isActive) throw invalidArgument("Target school is not active")
        // users.student_admission_number is unique platform-wide, so the admission number
        // travels with the learner unchanged and can never collide at the target school.
        val fromClassIds = classesOf(context(schoolId), student).map { it.id.toString() }
        val memberships = membershipRepository.findAllByStudentId(student.id)
        memberships.forEach { membership ->
            // Delete roster rows for this school only; another tenant's stale row is not ours to touch.
            classRepository.findById(membership.classId).orElse(null)?.let { clazz ->
                if (clazz.schoolId == schoolId) membershipRepository.deleteByClassIdAndStudentId(clazz.id, student.id)
            }
        }
        student.schoolId = targetSchoolId
        student.gradeLevel = null
        student.provisionedClassId = null
        student.joinedTeacherId = null
        student.referredByTeacherCode = null
        userRepository.saveAndFlush(student)

        auditLogService.record(schoolId, actor, "Transferred learner " + student.name + " to " + targetSchool.name + ": " + reason)
        auditLogService.record(targetSchoolId, actor, "Received learner " + student.name + " transferred from another school: " + reason)
        notifications.notifyUser(
            student.id,
            "School transfer",
            "You have been transferred to " + targetSchool.name + ".",
            type = NotificationType.SYSTEM,
            urgency = NotificationUrgency.HIGH,
        )
        return LearnerTransferResultPayload(
            studentId = student.id.toString(),
            kind = "SCHOOL",
            fromSchoolId = schoolId.toString(),
            toSchoolId = targetSchoolId.toString(),
            fromClassIds = fromClassIds,
            toClassId = null,
            message = "Transferred " + student.name + " to " + targetSchool.name,
        )
    }

    /** CSV cell: RFC 4180 quoting plus the spreadsheet formula-injection guard. */
    private fun csvCell(raw: String?): String {
        val value = raw.orEmpty()
        val dangerous = value.isNotEmpty() && (
            value[0] == '=' || value[0] == '+' || value[0] == '-' || value[0] == '@' ||
                value[0] == '\t' || value[0] == '\r'
            )
        val guarded = if (dangerous) "'" + value else value
        val needsQuotes = guarded.contains(',') || guarded.contains('"') || guarded.contains('\n') || guarded.contains('\r')
        return if (needsQuotes) "\"" + guarded.replace("\"", "\"\"") + "\"" else guarded
    }

    private companion object {
        const val MIN_PAGE_SIZE = 1
        const val MAX_PAGE_SIZE = 100
        const val MIN_REASON = 3
        val STATUS_FILTERS = listOf("ACTIVE", "INACTIVE") + AccountStatus.entries.map { it.name }
    }
}
