package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.PendingStudentPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The class teacher's learner-approval queue: the roster screen's "waiting for me" and
 * "rejected" lists (`GET /auth/students/pending|rejected?teacherCode=`).
 *
 * The decision endpoints (`POST /auth/students/{id}/approve|reject`) already live in
 * `TeacherAuthService`; these are the matching reads the web was missing, so the two can
 * never disagree about who is in the queue.
 *
 * Scope, derived from the token, never from the client: a teacher reads the queue of a
 * code they own; a coordinator only learners of the grade they actually coordinate; the
 * ICT admin / platform admin the whole school. An unassigned coordinator (a legacy row
 * with no grade) sees nothing rather than a school-wide list.
 */
@Service
class StudentApprovalQueueService(
    private val userRepository: UserRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val classRepository: TeacherClassRepository,
    private val gradeAuthority: GradeAuthority,
) {

    @Transactional(readOnly = true)
    fun pending(current: CurrentUser, teacherCodeRaw: String?): List<PendingStudentPayload> =
        queue(current, teacherCodeRaw, AccountStatus.PENDING_VERIFICATION)

    @Transactional(readOnly = true)
    fun rejected(current: CurrentUser, teacherCodeRaw: String?): List<PendingStudentPayload> =
        queue(current, teacherCodeRaw, AccountStatus.REJECTED)

    private fun queue(
        current: CurrentUser,
        teacherCodeRaw: String?,
        status: AccountStatus,
    ): List<PendingStudentPayload> {
        val actor = userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")
        if (actor.role != Role.TEACHER && actor.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Staff access required")
        }
        val code = resolveCode(actor, teacherCodeRaw)
        val students = userRepository.findByJoinedTeacherId(code.teacherUserId)
            .filter {
                it.role == Role.STUDENT &&
                    it.accountKind == AccountKind.FULL &&
                    it.verificationStatus == status
            }
        val visible = visibleTo(actor, code, students)
        // The grade a pending learner joined: their own grade if it is set, otherwise the
        // grade of the class the code enrols into (a signup carries no grade of its own).
        val joinedGrade = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(code.teacherUserId)
            .map { it.gradeLevel }
            .distinct()
            .singleOrNull()
        return visible.sortedBy { it.name.lowercase() }.map { student ->
            PendingStudentPayload(
                id = student.id.toString(),
                name = student.name,
                phoneNumber = student.phoneNumber.orEmpty(),
                admissionNumber = student.studentAdmissionNumber,
                grade = student.gradeLevel?.takeIf { it.isNotBlank() } ?: joinedGrade,
                referredByTeacherCode = student.referredByTeacherCode,
                joinedAt = student.createdAt.toEpochMilli(),
            )
        }
    }

    /**
     * A missing code falls back to the caller's own code; any other code must belong to a
     * teacher the caller may oversee. The code itself is not a secret (learners share it),
     * so it can never be the authorization on its own.
     */
    private fun resolveCode(actor: UserEntity, raw: String?): TeacherCodeEntity {
        val requested = raw?.trim()?.takeIf { it.isNotEmpty() }
        if (requested == null) {
            if (actor.role != Role.TEACHER) throw invalidArgument("teacherCode is required")
            return teacherCodeRepository.findByTeacherUserIdAndActiveTrue(actor.id)
                ?: throw notFound("No class code has been issued")
        }
        val code = teacherCodeRepository.findByCodeAndActiveTrue(requested.uppercase())
            ?: throw notFound("Unknown teacher code")
        if (code.teacherUserId == actor.id) return code
        if (gradeAuthority.isPlatformAdmin(actor)) return code
        if (!gradeAuthority.isInSchool(actor, code.schoolId)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your school")
        }
        if (gradeAuthority.isSchoolWide(actor) || gradeAuthority.isCoordinator(actor)) return code
        throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class code")
    }

    private fun visibleTo(
        actor: UserEntity,
        code: TeacherCodeEntity,
        students: List<UserEntity>,
    ): List<UserEntity> {
        if (gradeAuthority.isPlatformAdmin(actor)) return students
        if (code.teacherUserId == actor.id) return students
        if (!gradeAuthority.isSchoolWide(actor)) {
            // A coordinator only sees the learners of the grade they coordinate; without an
            // assignment (legacy row) that is nobody, never the whole school.
            if (!gradeAuthority.isCoordinator(actor)) return emptyList()
            return students.filter { gradeAuthority.canReachLearner(actor, it) }
        }
        return students
    }
}
