package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * One grade coordinator per grade (product rule; a coordinator is a class teacher with
 * grade-wide duties, so each grade has exactly one).
 *
 * The check runs under a school-row lock so two concurrent assignments cannot both pass:
 * the lock lives for the caller's transaction, and every call site is already transactional
 * (the same check-and-insert pattern the report quota uses). A rejected, frozen or deactivated
 * row frees the slot because those accounts are inactive.
 */
@Component
class CoordinatorGradeGuard(
    private val users: UserRepository,
    private val schools: SchoolRepository,
) {

    /**
     * Refuses a second coordinator for [gradeLevel] at [schoolId], ignoring [excludingUserId]
     * when an existing row is being moved or reactivated.
     *
     * @param blankGradeMessage the complaint when the account has no grade on record; the
     * signup, console and transfer flows each need different wording.
     */
    fun requireAvailable(
        schoolId: UUID?,
        gradeLevel: String?,
        excludingUserId: UUID? = null,
        blankGradeMessage: String = "Select the one grade you coordinate.",
    ) {
        val school = schoolId ?: throw invalidArgument("A grade coordinator must belong to a school.")
        val grade = gradeLevel?.trim()?.takeIf { it.isNotEmpty() } ?: throw invalidArgument(blankGradeMessage)
        schools.findByIdForUpdate(school) ?: throw notFound("School not found")
        val taken = users.findAllBySchoolIdAndSubRoleAndIsActiveTrue(school, SubRole.GRADE_COORDINATOR)
            .firstOrNull { it.id != excludingUserId && it.gradeLevel?.trim()?.equals(grade, ignoreCase = true) == true }
        if (taken != null) {
            throw conflict(
                grade + " already has a grade coordinator at this school. Move that assignment before adding another.",
            )
        }
    }
}
