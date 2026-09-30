package com.afrithecus.brainbox.api.classes

import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.StudentClassTransitionEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.StudentClassTransitionRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classes.web.TransitionCandidatePayload
import com.afrithecus.brainbox.api.classes.web.TransitionHistoryPayload
import com.afrithecus.brainbox.api.classes.web.TransitionRequest
import com.afrithecus.brainbox.api.classes.web.TransitionResultPayload
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Learner search and grade/stream transitions (docs/ongoing/product_ops_roadmap.md item 3).
 *
 * Rules:
 * - **Search is school-wide** so a class teacher can find a learner who is currently in
 *   another grade; filters narrow by name, grade and stream.
 * - **A class teacher may only move a learner into a class they own.** Coordinators and
 *   ICT admins may move within the whole school. (The coordinator's on/off switch in
 *   item 4 gates every move.)
 * - A move joins the target class and leaves **every other class** the learner was in, so
 *   both "same stream, different class" and "another grade, another stream" are real moves.
 *   Joining every subject class of the target grade is deliberately *not* implied — that is
 *   the group-move question still open in the roadmap.
 * - Every move is recorded in [StudentClassTransitionEntity] and notifies the learner and
 *   their guardian.
 */
@Service
class ClassTransitionService(
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val transitionRepository: StudentClassTransitionRepository,
    private val userRepository: UserRepository,
    private val settings: SchoolSystemSettingsRepository,
    private val notifications: NotificationService,
) {

    /** The coordinator's switch; enabled unless a school explicitly turns it off. */
    @Transactional(readOnly = true)
    fun transitionsEnabled(actor: UserEntity): Boolean {
        val schoolId = actor.schoolId ?: return true
        return settings.findById(schoolId).map { it.transitionsEnabled }.orElse(true)
    }

    /** Only a grade coordinator (or ICT admin) may flip the switch. */
    @Transactional
    fun setTransitionsEnabled(actor: UserEntity, enabled: Boolean): Boolean {
        if (!isSchoolWide(actor)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only a grade coordinator can change grade transitioning")
        }
        val schoolId = actor.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "You must belong to a school")
        val row = settings.findById(schoolId).orElseGet {
            SchoolSystemSettingsEntity().apply { this.schoolId = schoolId }
        }
        row.transitionsEnabled = enabled
        row.updatedAt = Instant.now()
        settings.save(row)
        return enabled
    }

    @Transactional(readOnly = true)
    fun search(
        actor: UserEntity,
        query: String?,
        grade: String?,
        stream: String?,
        classId: String?,
        limit: Int,
    ): List<TransitionCandidatePayload> {
        requireTeacher(actor)
        val schoolId = actor.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "You must belong to a school to search learners")
        val capped = limit.coerceIn(1, 50)

        val students: List<UserEntity> = if (!classId.isNullOrBlank()) {
            val clazz = classRepository.findById(parseUuid(classId, "classId")).orElse(null)
                ?: throw notFound("Class not found")
            if (clazz.schoolId != null && clazz.schoolId != schoolId) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Class is not in your school")
            }
            userRepository.findAllById(membershipRepository.findAllByClassId(clazz.id).map { it.studentId })
                .filter { it.isActive && it.role == Role.STUDENT }
        } else {
            userRepository.search(
                Role.STUDENT,
                schoolId,
                true,
                query?.trim()?.takeIf { it.isNotEmpty() },
                PageRequest.of(0, capped),
            ).content
        }

        val classesById = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .associateBy { it.id }
        val gradeFilter = grade?.trim()?.takeIf { it.isNotEmpty() }
        val streamFilter = stream?.trim()?.takeIf { it.isNotEmpty() }

        return students.asSequence().mapNotNull { student ->
            val current = membershipRepository.findAllByStudentId(student.id)
                .mapNotNull { classesById[it.classId] }
            val gradeOk = gradeFilter == null ||
                current.any { it.gradeLevel.equals(gradeFilter, ignoreCase = true) } ||
                (student.gradeLevel ?: "").equals(gradeFilter, ignoreCase = true)
            val streamOk = streamFilter == null ||
                current.any { (it.stream ?: "").equals(streamFilter, ignoreCase = true) }
            if (!gradeOk || !streamOk) return@mapNotNull null
            val primary = current.firstOrNull()
            TransitionCandidatePayload(
                studentId = student.id.toString(),
                name = student.name,
                admissionNumber = student.studentAdmissionNumber,
                grade = primary?.gradeLevel ?: student.gradeLevel,
                stream = primary?.stream,
                classId = primary?.id?.toString(),
                className = primary?.name,
            )
        }.take(capped).toList()
    }

    @Transactional
    fun transition(actor: UserEntity, studentIdRaw: String, request: TransitionRequest): TransitionResultPayload {
        requireTeacher(actor)
        // The coordinator can switch the whole operation off (item 4); a platform
        // admin is never blocked by a school-level switch.
        if (actor.role != Role.ADMIN && !transitionsEnabled(actor)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Grade transitioning is currently switched off by your coordinator")
        }
        val studentId = parseUuid(studentIdRaw, "studentId")
        val student = userRepository.findById(studentId).orElse(null) ?: throw notFound("Student not found")
        if (student.role != Role.STUDENT) throw invalidArgument("Only STUDENT accounts can be transitioned")
        val schoolId = actor.schoolId
        if (schoolId != null && student.schoolId != null && student.schoolId != schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Learner is not in your school")
        }

        val target = classRepository.findById(parseUuid(request.toClassId, "toClassId")).orElse(null)
            ?: throw notFound("Target class not found")
        if (target.schoolId != null && schoolId != null && target.schoolId != schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Target class is not in your school")
        }
        // A class teacher may only receive learners into a class they own.
        if (!isSchoolWide(actor) && target.teacherUserId != actor.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "You can only move learners into your own class")
        }

        val current = membershipRepository.findAllByStudentId(studentId)
            .mapNotNull { classRepository.findById(it.classId).orElse(null) }
            .filter { it.schoolId == null || schoolId == null || it.schoolId == schoolId }
        // A learner belongs to one class at a time, so a move leaves every other
        // membership — same grade or different. (Joining the target grade's other
        // subject classes is the group-move question still open in the roadmap.)
        val leaving = current.filter { it.id != target.id }
        leaving.forEach { membershipRepository.deleteByClassIdAndStudentId(it.id, studentId) }

        if (membershipRepository.findByClassIdAndStudentId(target.id, studentId) == null) {
            membershipRepository.save(
                ClassMembershipEntity().apply {
                    this.classId = target.id
                    this.studentId = studentId
                }
            )
        }
        if (target.gradeLevel.isNotBlank()) {
            student.gradeLevel = target.gradeLevel
            userRepository.save(student)
        }

        val mode = request.mode?.trim()?.uppercase()?.takeIf { it in MODES } ?: "PUSH"
        transitionRepository.save(
            StudentClassTransitionEntity().apply {
                this.studentId = studentId
                fromClassId = leaving.firstOrNull()?.id
                toClassId = target.id
                this.mode = mode
                reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
                initiatedBy = actor.id
                this.schoolId = schoolId
            }
        )

        val where = target.name + " (" + target.gradeLevel + (target.stream?.let { ", " + it } ?: "") + ")"
        notifications.notifyUser(
            studentId,
            "Class change",
            "You have been moved to " + where + ".",
            type = NotificationType.SYSTEM,
            urgency = NotificationUrgency.HIGH,
            actionRoute = "my_classes",
            actionLabel = "View class",
        )
        student.parentUserId?.let { parentId ->
            notifications.notifyUser(
                parentId,
                "Class change",
                student.name + " has been moved to " + where + ".",
                type = NotificationType.SYSTEM,
                urgency = NotificationUrgency.NORMAL,
            )
        }

        return TransitionResultPayload(
            studentId = studentId.toString(),
            fromClassId = leaving.firstOrNull()?.id?.toString(),
            toClassId = target.id.toString(),
            removedFromClassIds = leaving.map { it.id.toString() },
            mode = mode,
            message = "Moved " + student.name + " to " + where,
        )
    }

    @Transactional(readOnly = true)
    fun history(actor: UserEntity, studentIdRaw: String): List<TransitionHistoryPayload> {
        requireTeacher(actor)
        val studentId = parseUuid(studentIdRaw, "studentId")
        return transitionRepository.findAllByStudentIdOrderByCreatedAtDesc(studentId).map { row ->
            TransitionHistoryPayload(
                id = row.id.toString(),
                studentId = row.studentId.toString(),
                fromClassId = row.fromClassId?.toString(),
                toClassId = row.toClassId.toString(),
                mode = row.mode,
                reason = row.reason,
                initiatedBy = row.initiatedBy.toString(),
                createdAt = row.createdAt.toEpochMilli(),
            )
        }
    }

    private fun isSchoolWide(actor: UserEntity): Boolean =
        actor.role == Role.ADMIN ||
            (actor.role == Role.TEACHER &&
                (actor.subRole == SubRole.GRADE_COORDINATOR || actor.subRole == SubRole.ICT_ADMIN))

    private fun requireTeacher(actor: UserEntity) {
        if (actor.role != Role.TEACHER && actor.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
        }
    }

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private companion object {
        val MODES = setOf("PULL", "PUSH", "PROMOTE")
    }
}
