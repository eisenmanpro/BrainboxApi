package com.afrithecus.brainbox.api.teacher

import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.curriculum.SubjectCatalogService
import com.afrithecus.brainbox.api.curriculum.web.SubjectPayload
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.AdminSchoolAccess
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherProfileRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import com.afrithecus.brainbox.api.teacher.entity.TeacherChangeRequestEntity
import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import com.afrithecus.brainbox.api.teacher.model.TeacherChangeKind
import com.afrithecus.brainbox.api.teacher.model.TeacherChangeStatus
import com.afrithecus.brainbox.api.teacher.repository.TeacherChangeRequestRepository
import com.afrithecus.brainbox.api.teacher.repository.TeacherSettingsRepository
import com.afrithecus.brainbox.api.teacher.web.CreateTeacherChangeRequest
import com.afrithecus.brainbox.api.identity.web.SchoolClassPayload
import com.afrithecus.brainbox.api.teacher.web.TeacherChangeRequestPayload
import com.afrithecus.brainbox.api.teacher.web.TeacherSchoolConfigPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * The teacher change-request gate (N3).
 *
 * A teacher never writes a school-admin-governed field (class assignment,
 * school, subjects) directly: they submit a request, the destination school's
 * admin approves or rejects it, and only the approving transaction applies the
 * change. The row keeps a before/after snapshot so the approval gate can show
 * the full provenance, including whether the teacher is moving within the
 * school or arriving from another one.
 */
@Service
class TeacherChangeRequestService(
    private val requests: TeacherChangeRequestRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val schoolConfigRepository: SchoolConfigRepository,
    private val settingsRepository: TeacherSettingsRepository,
    private val teacherProfileRepository: TeacherProfileRepository,
    private val classRepository: TeacherClassRepository,
    private val subjectCatalog: SubjectCatalogService,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val notifications: NotificationService,
    private val auditLogService: AuditLogService,
    private val access: AdminSchoolAccess,
    private val clock: Clock,
) {

    // ------------------------------------------------------------ teacher side

    /** The drop-down source for the teacher settings screen: classes + subjects. */
    @Transactional(readOnly = true)
    fun schoolConfigFor(teacher: UserEntity): TeacherSchoolConfigPayload {
        requireTeacher(teacher)
        val schoolId = teacher.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A teacher must belong to a school to load settings options")
        val school = schoolRepository.findById(schoolId).orElseThrow { notFound("School not found") }
        return TeacherSchoolConfigPayload(
            schoolId = schoolId.toString(),
            schoolName = school.name,
            classes = classesFor(schoolId),
            subjects = subjectCatalog.all(),
        )
    }

    @Transactional(readOnly = true)
    fun mine(current: CurrentUser): List<TeacherChangeRequestPayload> {
        val teacher = requireTeacherUser(current)
        return payloads(requests.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id), actor = null)
    }

    @Transactional
    fun create(current: CurrentUser, request: CreateTeacherChangeRequest): TeacherChangeRequestPayload {
        val teacher = requireTeacherUser(current)
        val kind = enumOr(request.kind)
        val homeSchoolId = teacher.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "A teacher must belong to a school to request a change")
        if (requests.findAllByTeacherIdAndStatusOrderByCreatedAtDesc(teacher.id, TeacherChangeStatus.PENDING).isNotEmpty()) {
            throw conflict("You already have a pending change request. Wait for the school admin to decide it before submitting another.")
        }
        var decidingSchoolId = homeSchoolId
        val proposed: Map<String, Any?>
        val currentValues: Map<String, Any?>
        when (kind) {
            TeacherChangeKind.CLASS_ASSIGNMENT -> {
                val config = schoolConfigFor(teacher)
                val className = request.className?.trim().orEmpty()
                if (className.isEmpty()) throw invalidArgument("Choose the class you are assigned to")
                val option = config.classes.firstOrNull { it.name.equals(className, ignoreCase = true) }
                    ?: throw invalidArgument("That class is not in your school's class list. Ask the school admin to add it first.")
                val knownGrades = config.classes.map { it.gradeLevel }.distinct()
                val requestedGrades = request.gradeLevels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (requestedGrades.isEmpty()) throw invalidArgument("Choose at least one grade you teach")
                val grades = requestedGrades.map { raw ->
                    knownGrades.firstOrNull { it.equals(raw, ignoreCase = true) }
                        ?: throw invalidArgument("Grade " + raw + " is not in your school's class list")
                }
                proposed = linkedMapOf("className" to option.name, "gradeLevels" to grades)
                currentValues = currentClassAssignment(teacher)
            }
            TeacherChangeKind.SUBJECTS -> {
                if (request.subjects.isEmpty()) throw invalidArgument("Choose at least one subject")
                if (request.subjects.size > MAX_SUBJECTS) {
                    throw invalidArgument("Choose at most " + MAX_SUBJECTS + " subjects")
                }
                val subjects = request.subjects.map { raw ->
                    subjectCatalog.canonicalName(raw)
                        ?: throw invalidArgument("Unknown subject: " + raw.trim())
                }.distinct()
                proposed = linkedMapOf("subjects" to subjects)
                currentValues = linkedMapOf("subjects" to currentSubjects(teacher))
            }
            TeacherChangeKind.SCHOOL -> {
                val targetId = parseUuid(request.schoolId, "schoolId")
                val target = schoolRepository.findById(targetId).orElse(null) ?: throw notFound("School not found")
                if (!target.isActive) throw invalidArgument("That school is not accepting teachers")
                if (target.id == homeSchoolId) throw invalidArgument("You already teach at that school")
                val currentName = schoolRepository.findById(homeSchoolId).orElse(null)?.name.orEmpty()
                proposed = linkedMapOf("schoolId" to target.id.toString(), "schoolName" to target.name)
                currentValues = linkedMapOf("schoolId" to homeSchoolId.toString(), "schoolName" to currentName)
                decidingSchoolId = target.id
            }
        }
        val row = TeacherChangeRequestEntity().apply {
            teacherId = teacher.id
            this.kind = kind
            this.schoolId = decidingSchoolId
            this.homeSchoolId = homeSchoolId
            currentValue = mapper.writeValueAsString(currentValues)
            proposedValue = mapper.writeValueAsString(proposed)
            status = TeacherChangeStatus.PENDING
        }
        val saved = requests.saveAndFlush(row)
        auditLogService.record(decidingSchoolId, teacher, "Requested " + kindLabel(kind) + " change")
        notifyAdmins(saved, teacher, homeSchoolId)
        return payload(saved, teacher, actor = null)
    }

    // -------------------------------------------------------------- admin side

    @Transactional(readOnly = true)
    fun forSchool(current: CurrentUser, schoolIdRaw: String): List<TeacherChangeRequestPayload> {
        val schoolId = parseUuid(schoolIdRaw, "schoolId")
        val actor = access.require(current, schoolId)
        return payloads(requests.findAllForSchool(schoolId), actor)
    }

    @Transactional
    fun approve(current: CurrentUser, schoolIdRaw: String, requestIdRaw: String, note: String?): TeacherChangeRequestPayload {
        return decide(current, schoolIdRaw, requestIdRaw, note, approve = true)
    }

    @Transactional
    fun reject(current: CurrentUser, schoolIdRaw: String, requestIdRaw: String, note: String?): TeacherChangeRequestPayload {
        return decide(current, schoolIdRaw, requestIdRaw, note, approve = false)
    }

    private fun decide(
        current: CurrentUser,
        schoolIdRaw: String,
        requestIdRaw: String,
        note: String?,
        approve: Boolean,
    ): TeacherChangeRequestPayload {
        val schoolId = parseUuid(schoolIdRaw, "schoolId")
        val actor = access.require(current, schoolId)
        val requestId = parseUuid(requestIdRaw, "requestId")
        val row = requests.findById(requestId).orElse(null) ?: throw notFound("Change request not found")
        if (row.schoolId != schoolId) throw notFound("Change request not found in this school")
        val teacher = userRepository.findById(row.teacherId).orElse(null) ?: throw notFound("Teacher not found")
        // Offline replay: repeated decisions must be idempotent, not a second apply.
        if (row.status == TeacherChangeStatus.APPROVED && approve) return payload(row, teacher, actor)
        if (row.status == TeacherChangeStatus.REJECTED && !approve) return payload(row, teacher, actor)
        if (row.status != TeacherChangeStatus.PENDING) {
            throw conflict("This request was already " + row.status.name.lowercase() + " by another admin")
        }
        if (approve) apply(row, teacher)
        row.status = if (approve) TeacherChangeStatus.APPROVED else TeacherChangeStatus.REJECTED
        row.reviewerId = actor.id
        row.reviewNote = note?.trim()?.takeIf { it.isNotEmpty() }
        row.reviewedAt = clock.instant()
        requests.saveAndFlush(row)
        auditLogService.record(
            schoolId,
            actor,
            (if (approve) "Approved " else "Rejected ") + kindLabel(row.kind) + " change for " + teacher.name,
        )
        notifyTeacher(row, teacher, approve)
        return payload(row, teacher, actor)
    }

    // -------------------------------------------------------------- internals

    private fun apply(row: TeacherChangeRequestEntity, teacher: UserEntity) {
        val proposed = jsonMap(row.proposedValue)
        when (row.kind) {
            TeacherChangeKind.CLASS_ASSIGNMENT -> {
                val settings = settingsFor(teacher)
                settings.primaryClassName = proposed["className"] as? String
                settings.gradesTaught = codec.toJson(strings(proposed["gradeLevels"]))
                firstGrade(proposed)?.let { settings.defaultGradeLevel = it }
                settingsRepository.saveAndFlush(settings)
            }
            TeacherChangeKind.SUBJECTS -> {
                val settings = settingsFor(teacher)
                settings.subjectsTaught = codec.toJson(strings(proposed["subjects"]))
                settingsRepository.saveAndFlush(settings)
            }
            TeacherChangeKind.SCHOOL -> {
                val targetId = parseUuid(proposed["schoolId"] as? String, "proposed schoolId")
                schoolRepository.findById(targetId).orElse(null) ?: throw notFound("The destination school no longer exists")
                teacher.schoolId = targetId
                userRepository.save(teacher)
                teacherProfileRepository.findByUserId(teacher.id)?.let {
                    it.schoolId = targetId
                    teacherProfileRepository.save(it)
                }
            }
        }
    }

    private fun notifyAdmins(row: TeacherChangeRequestEntity, teacher: UserEntity, homeSchoolId: UUID) {
        val decidingSchool = schoolRepository.findById(row.schoolId).orElse(null)
        val targetName = decidingSchool?.name.orEmpty()
        val admins = adminsOf(row.schoolId)
        admins.forEach { admin ->
            notifications.notifyUser(
                userId = admin.id,
                title = "Teacher change request",
                message = teacher.name + " requested a " + kindLabel(row.kind) +
                    (if (row.kind == TeacherChangeKind.SCHOOL) " to " + targetName else "") +
                    ". Open Teacher approvals to approve or reject it.",
                type = NotificationType.SYSTEM,
                urgency = NotificationUrgency.HIGH,
                priority = NotificationPriority.HIGH,
                actionRoute = ADMIN_ROUTE,
                actionLabel = "Review request",
            )
        }
        // A transfer also concerns the school the teacher is leaving; its admin sees it
        // in the same queue (read-only) so a departure is never silent.
        if (row.schoolId != homeSchoolId) {
            adminsOf(homeSchoolId).filter { admin -> admins.none { it.id == admin.id } }.forEach { admin ->
                notifications.notifyUser(
                    userId = admin.id,
                    title = "Teacher transfer request",
                    message = teacher.name + " asked to move to " + targetName + ". The destination school's admin decides.",
                    type = NotificationType.SYSTEM,
                    urgency = NotificationUrgency.NORMAL,
                    priority = NotificationPriority.NORMAL,
                    actionRoute = ADMIN_ROUTE,
                    actionLabel = "View request",
                )
            }
        }
    }

    private fun notifyTeacher(row: TeacherChangeRequestEntity, teacher: UserEntity, approved: Boolean) {
        val outcome = if (approved) "approved" else "rejected"
        val route = when (row.kind) {
            TeacherChangeKind.CLASS_ASSIGNMENT -> CLASS_ROUTE
            TeacherChangeKind.SUBJECTS -> PROFILE_ROUTE
            TeacherChangeKind.SCHOOL -> SETTINGS_ROUTE
        }
        notifications.notifyUser(
            userId = teacher.id,
            title = "Change request " + outcome,
            message = "Your request to change your " + kindLabel(row.kind) + " was " + outcome + "." +
                (row.reviewNote?.let { " Note: " + it } ?: "") +
                (if (approved) " Your profile now shows the approved details." else ""),
            type = NotificationType.SYSTEM,
            urgency = NotificationUrgency.HIGH,
            priority = NotificationPriority.HIGH,
            actionRoute = route,
            actionLabel = "Open settings",
        )
    }

    private fun adminsOf(schoolId: UUID): List<UserEntity> =
        userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.role == Role.TEACHER && it.subRole == SubRole.ICT_ADMIN }

    private fun settingsFor(teacher: UserEntity): TeacherSettingsEntity =
        settingsRepository.findByTeacherId(teacher.id)
            ?: TeacherSettingsEntity().apply { teacherId = teacher.id }

    private fun currentClassAssignment(teacher: UserEntity): Map<String, Any?> {
        val settings = settingsRepository.findByTeacherId(teacher.id)
        val approvedName = settings?.primaryClassName?.takeIf { it.isNotBlank() }
        val approvedGrades = codec.parseList(settings?.gradesTaught).orEmpty()
        if (approvedName != null || approvedGrades.isNotEmpty()) {
            return linkedMapOf("className" to approvedName, "gradeLevels" to approvedGrades)
        }
        val classes = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
        return linkedMapOf(
            "className" to classes.firstOrNull()?.name,
            "gradeLevels" to classes.map { it.gradeLevel }.distinct(),
        )
    }

    private fun currentSubjects(teacher: UserEntity): List<String> {
        val settings = settingsRepository.findByTeacherId(teacher.id)
        val stored = codec.parseList(settings?.subjectsTaught).orEmpty()
        if (stored.isNotEmpty()) return stored
        return classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
            .map { it.subject }
            .distinct()
    }

    private fun classesFor(schoolId: UUID): List<SchoolClassPayload> {
        val json = schoolConfigRepository.findById(schoolId).orElse(null)?.classes ?: return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        val out = ArrayList<SchoolClassPayload>()
        for (index in 0 until node.size()) {
            val row = node.get(index)
            val name = row.get("name")?.asString()?.trim().orEmpty()
            val grade = row.get("gradeLevel")?.asString()?.trim().orEmpty()
            if (name.isEmpty() || grade.isEmpty()) continue
            out.add(
                SchoolClassPayload(
                    name = name,
                    gradeLevel = grade,
                    stream = row.get("stream")?.asString()?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }
        return out
    }

    private fun payloads(rows: List<TeacherChangeRequestEntity>, actor: UserEntity?): List<TeacherChangeRequestPayload> {
        if (rows.isEmpty()) return emptyList()
        val teachers = userRepository.findAllById(rows.map { it.teacherId }).associateBy { it.id }
        return rows.mapNotNull { row ->
            teachers[row.teacherId]?.let { payload(row, it, actor) }
        }
    }

    private fun payload(
        row: TeacherChangeRequestEntity,
        teacher: UserEntity,
        actor: UserEntity?,
    ): TeacherChangeRequestPayload {
        val currentValues = jsonMap(row.currentValue)
        val proposed = jsonMap(row.proposedValue)
        val homeSchool = row.homeSchoolId?.let { schoolRepository.findById(it).orElse(null) }
        val targetSchool = schoolRepository.findById(row.schoolId).orElse(null)
        val reviewer = row.reviewerId?.let { userRepository.findById(it).orElse(null) }
        val sameSchool = row.homeSchoolId == null || row.homeSchoolId == row.schoolId
        val canDecide = row.status == TeacherChangeStatus.PENDING &&
            actor != null &&
            (actor.role == Role.ADMIN || actor.schoolId == row.schoolId)
        return TeacherChangeRequestPayload(
            id = row.id.toString(),
            teacherId = teacher.id.toString(),
            teacherName = teacher.name,
            kind = row.kind.name,
            status = row.status.name,
            currentClassName = currentValues["className"] as? String,
            currentGradeLevels = strings(currentValues["gradeLevels"]),
            currentSubjects = strings(currentValues["subjects"]),
            proposedClassName = proposed["className"] as? String,
            proposedGradeLevels = strings(proposed["gradeLevels"]),
            proposedSubjects = strings(proposed["subjects"]),
            currentSchoolId = (currentValues["schoolId"] as? String) ?: row.homeSchoolId?.toString(),
            currentSchoolName = (currentValues["schoolName"] as? String) ?: homeSchool?.name,
            targetSchoolId = (proposed["schoolId"] as? String) ?: row.schoolId.toString(),
            targetSchoolName = (proposed["schoolName"] as? String) ?: targetSchool?.name,
            origin = if (sameSchool) "WITHIN_SCHOOL" else "FROM_OTHER_SCHOOL",
            canDecide = canDecide,
            createdAt = row.createdAt.toEpochMilli(),
            decidedAt = row.reviewedAt?.toEpochMilli(),
            reviewerName = reviewer?.name,
            reviewNote = row.reviewNote,
        )
    }

    private fun jsonMap(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (property in node.properties()) {
            val value = property.value
            out[property.key] = if (value.isArray) {
                (0 until value.size()).map { value.get(it).asString() }
            } else {
                value.asString()
            }
        }
        return out
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        is String -> if (value.isBlank()) emptyList() else listOf(value)
        else -> emptyList()
    }

    private fun firstGrade(proposed: Map<String, Any?>): Int? =
        strings(proposed["gradeLevels"]).firstNotNullOfOrNull { raw ->
            Regex("""\d{1,2}""").find(raw)?.value?.toIntOrNull()
        }

    private fun requireTeacherUser(current: CurrentUser): UserEntity {
        val user = userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")
        requireTeacher(user)
        return user
    }

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    private fun enumOr(raw: String): TeacherChangeKind =
        TeacherChangeKind.entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: throw invalidArgument("kind must be CLASS_ASSIGNMENT, SCHOOL or SUBJECTS")

    private fun parseUuid(raw: String?, field: String): UUID {
        if (raw.isNullOrBlank()) throw invalidArgument(field + " is required")
        return runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")
    }

    private fun kindLabel(kind: TeacherChangeKind): String = when (kind) {
        TeacherChangeKind.CLASS_ASSIGNMENT -> "class assignment"
        TeacherChangeKind.SCHOOL -> "school"
        TeacherChangeKind.SUBJECTS -> "subjects"
    }

    private companion object {
        const val MAX_SUBJECTS = 20
        const val ADMIN_ROUTE = "admin_user_management"
        const val SETTINGS_ROUTE = "teacher_settings"
        const val CLASS_ROUTE = "teacher_class_management"
        const val PROFILE_ROUTE = "teacher_profile_edit"
    }
}
