package com.afrithecus.brainbox.api.parent

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.parent.web.LinkChildRequest
import com.afrithecus.brainbox.api.parent.web.LinkedChildPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The parent portal's backbone: which learners a guardian may see.
 *
 * The link is `users.parent_user_id` on the learner (doc 01 §6), the same column the
 * grades, calendar, attendance, class-chat, subscription and notification surfaces
 * already authorize against, so exposing it here does not invent a second relationship.
 *
 * A guardian links a child with the learner's **admission number** (the CTC the school
 * issues): it is unique per school, printed on reports and the class roster, and does not
 * leak internal ids. A learner can have one guardian of record — a second guardian is
 * refused rather than silently stealing the link.
 */
@Service
class ParentPortalService(private val users: UserRepository) {

    /** The caller's linked children, oldest link first is not tracked: name order is stable. */
    @Transactional(readOnly = true)
    fun children(current: CurrentUser): List<LinkedChildPayload> =
        linkedChildren(current).map { it.toPayload() }

    /** Links a learner by admission number. Re-linking the same child is a no-op. */
    @Transactional
    fun linkChild(current: CurrentUser, request: LinkChildRequest): LinkedChildPayload {
        val parent = requireParent(current)
        val admissionNumber = request.admissionNumber.trim().takeIf { it.isNotEmpty() }
            ?: throw invalidArgument("admissionNumber is required")

        val child = users.findByStudentAdmissionNumber(admissionNumber)
            ?: throw notFound("No learner with that admission number")

        if (child.role != Role.STUDENT) throw notFound("No learner with that admission number")
        // A learner in another school is not this guardian's to claim. Answered as "not
        // found" so the endpoint cannot be used to probe other schools' rosters.
        if (parent.schoolId != null && child.schoolId != parent.schoolId) {
            throw notFound("No learner with that admission number")
        }
        if (!child.isActive) throw conflict("That learner's account is not active")
        if (child.parentUserId == parent.id) return child.toPayload()
        if (child.parentUserId != null) {
            throw conflict("That learner is already linked to another guardian")
        }

        child.parentUserId = parent.id
        return users.save(child).toPayload()
    }

    /** Removes a link the caller owns. Removing a link that is not theirs is a 404. */
    @Transactional
    fun unlinkChild(current: CurrentUser, childIdRaw: String) {
        requireParent(current)
        val childId = parseChildId(childIdRaw)
        val child = users.findById(childId).orElse(null)
            ?: throw notFound("Child not found")
        if (child.parentUserId != current.userId) throw notFound("Child not found")
        child.parentUserId = null
        users.save(child)
    }

    // ---------------------------------------------------------------- shared guards

    /**
     * The learners a guardian may read. Any parent-scoped read (grades, attendance,
     * homework, reports) resolves the child through here so the authorization rule lives
     * in one place.
     */
    @Transactional(readOnly = true)
    fun linkedChildren(current: CurrentUser): List<UserEntity> {
        requireParent(current)
        return users.findByParentUserId(current.userId)
            .filter { it.isActive }
            .sortedBy { it.name.lowercase() }
    }

    /** Resolves a child the caller is linked to, or fails as if it did not exist. */
    @Transactional(readOnly = true)
    fun requireLinkedChild(current: CurrentUser, childIdRaw: String): UserEntity {
        val childId = parseChildId(childIdRaw)
        val child = users.findById(childId).orElse(null) ?: throw notFound("Child not found")
        // An admin may read any child; a guardian only their own link. Anything else is
        // "not found" so a parent cannot enumerate other learners.
        val allowed = child.parentUserId == current.userId ||
            users.findById(current.userId).orElse(null)?.role == Role.ADMIN
        if (!allowed) throw notFound("Child not found")
        return child
    }

    private fun requireParent(current: CurrentUser): UserEntity {
        val user = users.findById(current.userId).orElseThrow { notFound("User not found") }
        if (user.role != Role.PARENT) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only a parent account can manage linked children")
        }
        return user
    }

    private fun parseChildId(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("childId is not a valid identifier")

    private fun UserEntity.toPayload() = LinkedChildPayload(
        id = id.toString(),
        name = name,
        grade = gradeLevel.orEmpty(),
        avatarUrl = null,
    )
}
