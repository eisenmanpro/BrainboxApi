package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.entity.ContentReviewEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.entity.ReviewerTrustEntity
import com.afrithecus.brainbox.api.content.repository.ContentReviewRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.repository.ReviewerTrustRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Phase 7.4a review workflow: records one reviewer decision per content version,
 * aggregates the moderation outcome, propagates the resolved state to the content
 * unit, and maintains the weight-only reviewer trust ladder.
 *
 * Quorum rule (locked in the Phase 7 architecture): a quorum needs at least
 * `quorumRequired` APPROVE decisions **and** at least two distinct approvers, so a
 * single high-weight expert can never approve content alone. Any REJECT resolves
 * the version to REJECTED immediately. Weighted approvals are captured for the
 * policy/analytics layer; the distinct-human cap applies regardless.
 *
 * Phase 7.5c: when a UNIT resolves to REVIEWED, the resolved unit is re-projected so
 * the human approval flips it learner-visible even if an earlier gate projected it
 * hidden. Machine-first approval skips non-UNREVIEWED units, so the human decision and
 * its reviewer attribution are never overwritten.
 */
@Service
class ReviewService(
    private val reviews: ContentReviewRepository,
    private val outcomes: ModerationOutcomeRepository,
    private val trust: ReviewerTrustRepository,
    private val contentUnits: ContentUnitRepository,
    private val users: UserRepository,
    private val moderationPolicy: ModerationPolicyService,
    private val mapper: ObjectMapper,
    private val contentProjection: ContentProjectionService,
) {

    /**
     * Upserts [actor]'s review for one content version, then recomputes and
     * persists the aggregated outcome. Re-deciding replaces the reviewer's row.
     */
    @Transactional
    fun recordDecision(
        actor: UserEntity,
        contentType: String,
        contentId: UUID,
        contentVersion: Int,
        decision: String,
        reasonTags: List<String>,
        comment: String?,
    ): ModerationOutcomeEntity {
        val normalizedType = contentType.trim().uppercase()
        if (normalizedType.isEmpty()) throw invalidArgument("content type must not be blank")
        val normalizedDecision = decision.trim().uppercase()
        if (normalizedDecision !in DECISIONS) {
            throw invalidArgument("unknown moderation decision: " + decision)
        }
        if (contentVersion < 1) throw invalidArgument("content version must be >= 1")

        val actorIsStaff = isStaff(actor)
        val actorWeight = if (actorIsStaff) STAFF_WEIGHT else trustFor(actor.id).weight

        val existing = reviews.findByReviewerIdAndContentTypeAndContentIdAndContentVersion(
            actor.id, normalizedType, contentId, contentVersion
        )
        val review = existing ?: ContentReviewEntity().apply {
            reviewerId = actor.id
            this.contentType = normalizedType
            this.contentId = contentId
            this.contentVersion = contentVersion
        }
        review.decision = normalizedDecision
        review.reasonTags = reasonTags.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
        review.comment = comment
        review.weight = actorWeight
        reviews.saveAndFlush(review)

        return resolve(
            contentType = normalizedType,
            contentId = contentId,
            contentVersion = contentVersion,
            created = existing == null,
            actorId = actor.id,
            actorIsStaff = actorIsStaff,
        )
    }

    @Transactional(readOnly = true)
    fun listDecisions(contentType: String, contentId: UUID, contentVersion: Int): List<ContentReviewEntity> =
        reviews.findAllByContentTypeAndContentIdAndContentVersion(
            contentType.trim().uppercase(), contentId, contentVersion
        )

    @Transactional(readOnly = true)
    fun outcome(contentType: String, contentId: UUID, contentVersion: Int): ModerationOutcomeEntity? =
        outcomes.findByContentTypeAndContentIdAndContentVersion(
            contentType.trim().uppercase(), contentId, contentVersion
        )

    // ------------------------------------------------------------- aggregation

    private fun resolve(
        contentType: String,
        contentId: UUID,
        contentVersion: Int,
        created: Boolean,
        actorId: UUID,
        actorIsStaff: Boolean,
    ): ModerationOutcomeEntity {
        val all = reviews.findAllByContentTypeAndContentIdAndContentVersion(contentType, contentId, contentVersion)
        val approvals = all.filter { it.decision == DECISION_APPROVE }
        val rejections = all.count { it.decision == DECISION_REJECT }
        val distinctApprovers = approvals.map { it.reviewerId }.distinct().size
        val weightedApprovals = approvals.sumOf { it.weight }
        val quorumRequired = moderationPolicy.quorumRequired()

        val quorumMet = approvals.size >= quorumRequired && distinctApprovers >= MIN_DISTINCT_APPROVERS
        val state = when {
            rejections > 0 -> STATE_REJECTED
            quorumMet -> STATE_REVIEWED
            else -> STATE_UNREVIEWED
        }

        val existingOutcome = outcomes.findByContentTypeAndContentIdAndContentVersion(
            contentType, contentId, contentVersion
        )
        val previousState = existingOutcome?.state
        val outcome = existingOutcome ?: ModerationOutcomeEntity().apply {
            this.contentType = contentType
            this.contentId = contentId
            this.contentVersion = contentVersion
        }
        outcome.approvals = approvals.size
        outcome.rejections = rejections
        outcome.weightedApprovals = weightedApprovals
        outcome.quorumRequired = quorumRequired
        outcome.state = state
        outcome.decidedAt = if (isResolved(state)) Instant.now() else null
        // A human decision always supersedes an auto-approval: clear the machine marker.
        outcome.autoApproved = false
        outcome.confidenceScore = null
        outcome.reviewerId = if (isResolved(state)) actorId else null
        outcomes.save(outcome)

        if (contentType == CONTENT_TYPE_UNIT) {
            contentUnits.findById(contentId).ifPresent { unit ->
                unit.reviewState = state
                contentUnits.save(unit)
            }
            // A human approval must actually flip the unit learner-visible: a unit that
            // failed a gate was projected hidden, so re-project the resolved REVIEWED
            // unit. Machine approval skips non-UNREVIEWED units, so this re-projection
            // cannot overwrite the human attribution above.
            if (state == STATE_REVIEWED) contentProjection.project(contentId)
        }

        updateTrust(all, state, previousState, created, actorId, actorIsStaff)
        return outcome
    }

    // ------------------------------------------------------------------ trust

    private fun updateTrust(
        all: List<ContentReviewEntity>,
        state: String,
        previousState: String?,
        created: Boolean,
        actorId: UUID,
        actorIsStaff: Boolean,
    ) {
        val reviewsDelta = mutableMapOf<UUID, Int>()
        val agreementsDelta = mutableMapOf<UUID, Int>()

        // A new decision counts once toward review volume; re-deciding a version
        // replaces the existing row and is not a new review.
        if (created && !actorIsStaff) reviewsDelta[actorId] = 1

        // Agreement is credited only on the transition into a resolved state, so
        // an already-decided version is never counted twice.
        if (state != previousState && isResolved(state)) {
            val matching = when (state) {
                STATE_REVIEWED -> all.filter { it.decision == DECISION_APPROVE }
                else -> all.filter { it.decision == DECISION_REJECT }
            }
            matching.map { it.reviewerId }.distinct()
                .filterNot { isStaffUserId(it) }
                .forEach { agreementsDelta[it] = (agreementsDelta[it] ?: 0) + 1 }
        }

        (reviewsDelta.keys + agreementsDelta.keys).forEach { teacherId ->
            adjustTrust(teacherId, reviewsDelta[teacherId] ?: 0, agreementsDelta[teacherId] ?: 0)
        }
    }

    private fun adjustTrust(teacherId: UUID, reviewsDelta: Int, agreementsDelta: Int) {
        if (reviewsDelta == 0 && agreementsDelta == 0) return
        val entity = trust.findByTeacherId(teacherId) ?: ReviewerTrustEntity().apply { this.teacherId = teacherId }
        entity.reviewsCount += reviewsDelta
        entity.agreements += agreementsDelta
        entity.tier = tierFor(entity.reviewsCount, entity.agreements)
        entity.weight = weightFor(entity.tier)
        trust.save(entity)
    }

    private fun trustFor(teacherId: UUID): ReviewerTrustEntity =
        trust.findByTeacherId(teacherId) ?: ReviewerTrustEntity().apply {
            this.teacherId = teacherId
            tier = 0
            reviewsCount = 0
            agreements = 0
            weight = STAFF_WEIGHT
        }

    private fun tierFor(reviewsCount: Int, agreements: Int): Int {
        if (reviewsCount <= 0) return 0
        val agreementRate = agreements.toDouble() / reviewsCount
        return when {
            reviewsCount >= TIER2_MIN_REVIEWS && agreementRate >= TIER2_MIN_AGREEMENT -> 2
            reviewsCount >= TIER1_MIN_REVIEWS && agreementRate >= TIER1_MIN_AGREEMENT -> 1
            else -> 0
        }
    }

    private fun weightFor(tier: Int): Double = minOf(1.0 + TIER_WEIGHT_STEP * tier, MAX_REVIEWER_WEIGHT)

    // -------------------------------------------------------------- moderation

    private fun isResolved(state: String): Boolean = state == STATE_REVIEWED || state == STATE_REJECTED

    private fun isStaff(user: UserEntity): Boolean =
        user.role == Role.ADMIN ||
            (user.role == Role.TEACHER && user.subRole == SubRole.ICT_ADMIN)

    private fun isStaffUserId(userId: UUID): Boolean =
        users.findById(userId).orElse(null)?.let { isStaff(it) } ?: false

    private companion object {
        const val CONTENT_TYPE_UNIT = "UNIT"
        const val DECISION_APPROVE = "APPROVE"
        const val DECISION_REJECT = "REJECT"
        const val DECISION_REQUEST_CHANGES = "REQUEST_CHANGES"
        val DECISIONS = setOf(DECISION_APPROVE, DECISION_REJECT, DECISION_REQUEST_CHANGES)

        const val STATE_UNREVIEWED = "UNREVIEWED"
        const val STATE_REVIEWED = "REVIEWED"
        const val STATE_REJECTED = "REJECTED"

        const val MIN_DISTINCT_APPROVERS = 2
        const val STAFF_WEIGHT = 1.0
        const val TIER_WEIGHT_STEP = 0.5
        const val MAX_REVIEWER_WEIGHT = 2.0
        const val TIER1_MIN_REVIEWS = 10
        const val TIER1_MIN_AGREEMENT = 0.80
        const val TIER2_MIN_REVIEWS = 50
        const val TIER2_MIN_AGREEMENT = 0.85
    }
}
