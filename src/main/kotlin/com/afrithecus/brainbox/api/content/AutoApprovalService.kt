package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import com.afrithecus.brainbox.api.content.validation.ValidationReport
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
 * O1: every refusal persists a stable reason code on the unit
 * (`content_units.auto_approve_blocked_reason`) so the exception reason is a queryable
 * fact rather than an in-process counter; a successful auto-approval clears it. The
 * reason is the first BLOCKER finding code, else the first finding code when the score
 * is below the validator minimum, else a fixed gate code (DISABLED, ALREADY_DECIDED,
 * ASSESSMENT_QUESTION_FLOOR, CONFIDENCE_MISSING, CONFIDENCE_LOW, ANSWER_KEY_UNVERIFIED,
 * ANSWER_KEY_DISAGREEMENT). It is written only when it changes, so a repeated refusal
 * does not churn the row or its optimistic version.
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
    /** H1 observability: approved/exception rate and the first gate that tripped. */
    private val metrics: ContentMetrics,
) {

    @Transactional
    fun maybeAutoApprove(contentType: String, contentId: UUID, contentVersion: Int = 1): Boolean {
        val type = contentType.trim().uppercase()

        // A disabled policy is global and is evaluated before the unit load, but the
        // refusal is still stamped on the unit when one can be identified.
        if (!moderationPolicy.autoApproveEnabled()) {
            if (type == ContentValidationService.CONTENT_TYPE_UNIT) {
                contentUnits.findById(contentId).orElse(null)?.let { persistBlockedReason(it, REASON_DISABLED) }
            }
            return gateException(REASON_POLICY_DISABLED)
        }
        if (type != ContentValidationService.CONTENT_TYPE_UNIT) return gateException(REASON_NOT_UNIT)

        // A human decision wins: only an untouched unit may be machine-approved.
        val unit = contentUnits.findById(contentId).orElse(null) ?: return gateException(REASON_NOT_FOUND)
        if (unit.reviewState != STATE_UNREVIEWED) {
            persistBlockedReason(unit, REASON_ALREADY_DECIDED)
            return gateException(REASON_HUMAN_DECISION)
        }

        val report = validation.validate(type, contentId)
        if (report.blockers) {
            persistBlockedReason(unit, firstBlockerCode(report))
            return gateException(reportReason(report, REASON_VALIDATOR_BLOCKER))
        }
        if (report.score < moderationPolicy.autoApproveMinValidatorScore()) {
            persistBlockedReason(unit, report.findings.firstOrNull()?.code ?: REASON_VALIDATOR_SCORE)
            return gateException(reportReason(report, REASON_VALIDATOR_SCORE))
        }

        val questionCount = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(contentId).size
        if (ContentTaskTypes.isAssessment(unit.taskType)) {
            // An assessment is measured by its questions, so the floor is unconditional:
            // a unit whose disputed keys were all dropped (questionCount 0) must fail the
            // gate rather than skip it (Phase 7.5h).
            if (questionCount < moderationPolicy.autoApproveMinQuestions()) {
                persistBlockedReason(unit, REASON_ASSESSMENT_QUESTION_FLOOR)
                return gateException(REASON_QUESTION_FLOOR)
            }
            val confidence = unit.confidence
            if (confidence == null) {
                persistBlockedReason(unit, REASON_CONFIDENCE_MISSING)
                return gateException(REASON_CRITIC_CONFIDENCE)
            }
            if (confidence < moderationPolicy.autoApproveMinCriticConfidence()) {
                persistBlockedReason(unit, REASON_CONFIDENCE_LOW)
                return gateException(REASON_CRITIC_CONFIDENCE)
            }
            // Phase 7.5f hard gate: an assessment's keys are only as good as an
            // independent solve. Fail closed when there is no verification, and
            // require the agreement ratio to clear the policy bar (default 1.0, every
            // surviving key agrees after the 7.5h disposition).
            if (unit.answerKeyVerifiedAt == null) {
                persistBlockedReason(unit, REASON_ANSWER_KEY_UNVERIFIED)
                return gateException(REASON_ANSWER_KEY_UNVERIFIED)
            }
            val agreement = unit.answerKeyAgreement
            if (agreement == null) {
                persistBlockedReason(unit, REASON_ANSWER_KEY_UNVERIFIED)
                return gateException(REASON_ANSWER_KEY_UNVERIFIED)
            }
            if (agreement < moderationPolicy.answerKeyMinAgreement()) {
                persistBlockedReason(unit, REASON_ANSWER_KEY_DISAGREEMENT)
                return gateException(REASON_ANSWER_KEY_AGREEMENT)
            }
        } else if (questionCount > 0) {
            // The question-count floor is an assessment rule. A NOTES/readable unit with a
            // few nested checks is exactly the BrainBox standard, so it must not be held to 8.
            val confidence = unit.confidence
            if (confidence == null) {
                persistBlockedReason(unit, REASON_CONFIDENCE_MISSING)
                return gateException(REASON_CRITIC_CONFIDENCE)
            }
            if (confidence < moderationPolicy.autoApproveMinCriticConfidence()) {
                persistBlockedReason(unit, REASON_CONFIDENCE_LOW)
                return gateException(REASON_CRITIC_CONFIDENCE)
            }
        }

        // O1: published by the machine, so the unit is no longer an exception.
        unit.autoApproveBlockedReason = null
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
        // O1 sampled audit: a stable content-id hash picks the sample, so the same
        // unit is always flagged or not, and the decision is reproducible across calls.
        outcome.auditSample = AuditSampling.isSampled(contentId, moderationPolicy.autoApproveAuditSamplePercent())
        outcomes.save(outcome)
        metrics.recordAutoApproved()
        return true
    }

    /**
     * Records one exception and returns false so every gate stays a one-liner. The
     * metric reason is the first blocker/warning code from the validator report when
     * the report ran, otherwise the name of the gate that failed.
     */
    private fun gateException(reason: String): Boolean {
        metrics.recordAutoApprovalException(reason)
        return false
    }

    /**
     * Persists the O1 exception reason, writing only when it actually changes so a
     * repeated refusal does not touch updated_at or the optimistic version.
     */
    private fun persistBlockedReason(unit: ContentUnitEntity, reason: String) {
        val code = reason.take(MAX_REASON_LENGTH)
        if (unit.autoApproveBlockedReason == code) return
        unit.autoApproveBlockedReason = code
        contentUnits.save(unit)
    }

    /** The first BLOCKER finding code; a report with blockers always has one. */
    private fun firstBlockerCode(report: ValidationReport): String =
        report.findings.firstOrNull { it.severity == FindingSeverity.BLOCKER }?.code ?: REASON_VALIDATOR_BLOCKER

    /** The first non-INFO finding code, then any finding, then [fallback]. */
    private fun reportReason(report: ValidationReport, fallback: String): String =
        report.findings.firstOrNull { it.severity != FindingSeverity.INFO }?.code
            ?: report.findings.firstOrNull()?.code
            ?: fallback

    private companion object {
        const val STATE_UNREVIEWED = "UNREVIEWED"
        const val STATE_REVIEWED = "REVIEWED"

        /** The persisted column is varchar(64); a finding code is far shorter. */
        const val MAX_REASON_LENGTH = 64

        // Live Micrometer metric reasons (unchanged H1 vocabulary).
        const val REASON_POLICY_DISABLED = "AUTO_APPROVE_DISABLED"
        const val REASON_NOT_UNIT = "UNSUPPORTED_TYPE"
        const val REASON_NOT_FOUND = "CONTENT_NOT_FOUND"
        const val REASON_HUMAN_DECISION = "HUMAN_DECISION"
        const val REASON_VALIDATOR_BLOCKER = "VALIDATOR_BLOCKER"
        const val REASON_VALIDATOR_SCORE = "VALIDATOR_SCORE"
        const val REASON_QUESTION_FLOOR = "QUESTION_FLOOR"
        const val REASON_CRITIC_CONFIDENCE = "CRITIC_CONFIDENCE"
        const val REASON_ANSWER_KEY_UNVERIFIED = "ANSWER_KEY_UNVERIFIED"
        const val REASON_ANSWER_KEY_AGREEMENT = "ANSWER_KEY_AGREEMENT"

        // Persisted O1 exception reason codes; the stable contract the ops rollup reads.
        const val REASON_DISABLED = "DISABLED"
        const val REASON_ALREADY_DECIDED = "ALREADY_DECIDED"
        const val REASON_ASSESSMENT_QUESTION_FLOOR = "ASSESSMENT_QUESTION_FLOOR"
        const val REASON_CONFIDENCE_MISSING = "CONFIDENCE_MISSING"
        const val REASON_CONFIDENCE_LOW = "CONFIDENCE_LOW"
        const val REASON_ANSWER_KEY_DISAGREEMENT = "ANSWER_KEY_DISAGREEMENT"
    }
}
