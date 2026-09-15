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
 *  4. if the unit has questions, its critic confidence is non-null and at least
 *     `auto_approve_min_critic_confidence` (default 0.90; a null confidence fails
 *     closed);
 *  5. if the unit is an assessment task type (QUIZ/EXAM/ASSESSMENT), it carries at
 *     least `auto_approve_min_questions` (default 8) unconditionally, so a unit whose
 *     disputed keys were all dropped (question count 0) fails the gate instead of
 *     skipping it (Phase 7.5h). A NOTES micro-lesson may carry a few nested checks
 *     without meeting 8, because the question count is not the product there;
 *  6. if the unit is an assessment task type, its answer keys have been independently
 *     verified and the agreement is at least `answer_key_min_agreement` (default 1.0).
 *     An unverified or partly-agreeing assessment fails closed into the exception queue
 *     (Phase 7.5f); after the 7.5h per-question disposition only the surviving keys are
 *     compared, so a dropped item never hides a wrong key;
 *  7. the unit is still `UNREVIEWED`, so a human decision (REVIEWED or REJECTED)
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
        if (ContentTaskTypes.isAssessment(unit.taskType)) {
            // An assessment is measured by its questions, so the floor is unconditional:
            // a unit whose disputed keys were all dropped (questionCount 0) must fail the
            // gate rather than skip it (Phase 7.5h).
            if (questionCount < moderationPolicy.autoApproveMinQuestions()) return false
            val confidence = unit.confidence ?: return false
            if (confidence < moderationPolicy.autoApproveMinCriticConfidence()) return false
            // Phase 7.5f hard gate: an assessment's keys are only as good as an
            // independent solve. Fail closed when there is no verification, and
            // require the agreement ratio to clear the policy bar (default 1.0, every
            // surviving key agrees after the 7.5h disposition).
            if (unit.answerKeyVerifiedAt == null) return false
            val agreement = unit.answerKeyAgreement ?: return false
            if (agreement < moderationPolicy.answerKeyMinAgreement()) return false
        } else if (questionCount > 0) {
            // The question-count floor is an assessment rule. A NOTES/readable unit with a
            // few nested checks is exactly the BrainBox standard, so it must not be held to 8.
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
