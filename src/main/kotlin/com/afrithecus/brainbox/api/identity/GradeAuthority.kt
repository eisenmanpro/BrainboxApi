package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The one rule for grade-coordinator authority (product rule: a coordinator is a
 * class teacher with grade-wide duties, exactly one per grade per school).
 *
 * A coordinator may act on their own school's data **for their own grade only**;
 * a platform ADMIN and the school's ICT ADMIN stay school-wide. Ordinary
 * assignment-based teaching access (a class the teacher owns, a learner they
 * teach) is *additive* and is checked separately by each service after the
 * predicates here return false - a coordinator who teaches a subject in another
 * grade keeps that teaching access, they just do not gain coordination rights
 * over that grade.
 *
 * Check order every caller should follow:
 * 1. [isPlatformAdmin] / [isSchoolWide]
 * 2. [isInSchool] (never cross a tenant)
 * 3. assignment-based access already implemented by the service
 * 4. [overseesGrade] / [canReachClass] / [canReachLearner] for coordinator grade scope
 *
 * The actor is always the persisted [UserEntity] loaded from the token, never a
 * client-supplied grade or school id. A coordinator with no grade on record (a
 * legacy row) has no grade-wide powers until an admin sets the grade.
 */
@Component
class GradeAuthority(
    private val memberships: ClassMembershipRepository,
    private val classes: TeacherClassRepository,
) {

    fun isPlatformAdmin(user: UserEntity): Boolean = user.role == Role.ADMIN

    fun isIctAdmin(user: UserEntity): Boolean =
        user.role == Role.TEACHER && user.subRole == SubRole.ICT_ADMIN

    /** Platform admin or the school's ICT admin: either sees a whole school. */
    fun isSchoolWide(user: UserEntity): Boolean = isPlatformAdmin(user) || isIctAdmin(user)

    fun isCoordinator(user: UserEntity): Boolean =
        user.role == Role.TEACHER && user.subRole == SubRole.GRADE_COORDINATOR

    /** The single grade a coordinator leads, or null for anyone else / a coordinator without one. */
    fun coordinatorGrade(user: UserEntity): String? =
        if (isCoordinator(user)) normalize(user.gradeLevel) else null

    /** True when [user] may exercise coordination authority over [gradeLevel]. */
    fun overseesGrade(user: UserEntity, gradeLevel: String?): Boolean {
        if (isSchoolWide(user)) return true
        if (!isCoordinator(user)) return false
        val own = coordinatorGrade(user) ?: return false
        val target = normalize(gradeLevel) ?: return false
        return own.equals(target, ignoreCase = true)
    }

    /** The actor belongs to [schoolId]; legacy rows with no school stay reachable. */
    fun isInSchool(user: UserEntity, schoolId: UUID?): Boolean =
        schoolId == null || user.schoolId == schoolId

    /**
     * Read/reach a class: platform admin (any tenant), ICT admin (own school),
     * the class owner, or a coordinator for a class in their own grade/school.
     */
    fun canReachClass(user: UserEntity, clazz: TeacherClassEntity): Boolean {
        if (isPlatformAdmin(user)) return true
        if (!isInSchool(user, clazz.schoolId)) return false
        if (isSchoolWide(user)) return true
        if (clazz.teacherUserId == user.id) return true
        return overseesGrade(user, clazz.gradeLevel)
    }

    /** Every grade a learner is attached to: their class memberships plus their own grade field. */
    fun learnerGrades(student: UserEntity): Set<String> {
        val grades = linkedSetOf<String>()
        normalize(student.gradeLevel)?.let { grades += it }
        memberships.findAllByStudentId(student.id).forEach { membership ->
            classes.findById(membership.classId).orElse(null)?.let { clazz ->
                normalize(clazz.gradeLevel)?.let { grades += it }
            }
        }
        return grades
    }

    /**
     * Coordination access to a learner: platform admin, ICT admin of the learner's
     * school, or a coordinator of one of the learner's grades. Assignment-based
     * access (sharing a class) is additive and remains the service's own check.
     */
    fun canReachLearner(user: UserEntity, student: UserEntity): Boolean {
        if (isPlatformAdmin(user)) return true
        if (!isInSchool(user, student.schoolId)) return false
        if (isSchoolWide(user)) return true
        if (!isCoordinator(user)) return false
        return learnerGrades(student).any { overseesGrade(user, it) }
    }

    /** The grades a teacher actually teaches (their owned class rows), any grade. */
    fun teachingGrades(teacherId: UUID): Set<String> =
        classes.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacherId)
            .mapNotNull { normalize(it.gradeLevel) }
            .toSet()

    private fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
