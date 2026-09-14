package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Phase 7.5c machine-first auto-approval. Auto-approval is the default bulk path:
 * a UNIT is approved by the machine only when every gate below holds, so a teacher
 * or Brainbox moderator only ever handles the exceptions (anything that fails a gate)
 * and can still override or unpublish.
 *
 * A unit auto-approves only when:
 *  1. the policy `auto_approve_enabled` is true (default true);
 *  2. the validator report has no blockers;
 *  3. the validator score is at least `auto_approve_min_validator_score`
 *     (default 1.0, i.e. zero findings);
 *  4. if the unit has questions, it carries at least `auto_approve_min_questions`
 *     (default 8);
 *  5. if the unit has questions, its critic confidence is non-null and at least
 *     `auto_approve_min_critic_confidence` (default 0.90; a null confidence fails
 *     closed);
 *  6. the unit is still `UNREVIEWED`, so a human decision (REVIEWED or REJECTED)
 *     is never touched, overwritten or re-attributed.
 *
 * An auto-approved outcome carries `auto_approved = true`, the validator score in
 * `confidence_score` and a null `reviewer_id`, so a machine decision is never
 * mistakable for a human one.
 */
@Service
class AutoApprovalService(
    private val moderationPolicy: ModerationPolicyService,
    private val validation: ContentValidationService,
    private val contentUnits: ContentUnitRepository,
    private val unitQuestions: ContentUnitQuestionRepository,
    private val outcomes: ModerationOutcomeRepository,
) {

    @Transactional
    fun maybeAutoApprove(contentType: String, contentId: UUID, contentVersion: Int = 1): Boolean {
        if (!moderationPolicy.autoApproveEnabled()) return false

        val type = contentType.trim().uppercase()
        if (type != ContentValidationService.CONTENT_TYPE_UNIT) return false

        // A human decision wins: only an untouched unit may be machine-approved.
        val unit = contentUnits.findById(contentId).orElse(null) ?: return false
        if (unit.reviewState != STATE_UNREVIEWED) return false

        val report = validation.validate(type, contentId)
        if (report.blockers) return false
        if (report.score < moderationPolicy.autoApproveMinValidatorScore()) return false

        val questionCount = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(contentId).size
        if (questionCount > 0) {
            if (questionCount < moderationPolicy.autoApproveMinQuestions()) return false
            val confidence = unit.confidence ?: return false
            if (confidence < moderationPolicy.autoApproveMinCriticConfidence()) return false
        }

        unit.reviewState = STATE_REVIEWED
        contentUnits.save(unit)

        val outcome = outcomes.findByContentTypeAndContentIdAndContentVersion(type, contentId, contentVersion)
            ?: ModerationOutcomeEntity().apply {
                this.contentType = type
                this.contentId = contentId
                this.contentVersion = contentVersion
            }
        outcome.state = STATE_REVIEWED
        outcome.autoApproved = true
        outcome.confidenceScore = report.score
        outcome.reviewerId = null
        outcome.quorumRequired = moderationPolicy.quorumRequired()
        outcome.decidedAt = Instant.now()
        outcomes.save(outcome)
        return true
    }

    private companion object {
        const val STATE_UNREVIEWED = "UNREVIEWED"
        const val STATE_REVIEWED = "REVIEWED"
    }
}
