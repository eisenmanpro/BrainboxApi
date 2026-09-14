package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.ValidationReport
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Phase 7.4b confidence gate. Auto-approval is off unless the policy enables it, only
 * applies to UNIT content that validates with no blockers, and must clear the policy
 * threshold plus the per-domain floor (curriculum/structure 1.0, answer-key 0.995).
 *
 * An auto-approved outcome carries auto_approved = true and a null reviewer_id, so a
 * machine decision is never mistakable for a human one.
 */
@Service
class AutoApprovalService(
    private val moderationPolicy: ModerationPolicyService,
    private val validation: ContentValidationService,
    private val contentUnits: ContentUnitRepository,
    private val outcomes: ModerationOutcomeRepository,
) {

    @Transactional
    fun maybeAutoApprove(contentType: String, contentId: UUID, contentVersion: Int = 1): Boolean {
        if (!moderationPolicy.autoApproveEnabled()) return false

        val type = contentType.trim().uppercase()
        if (type != ContentValidationService.CONTENT_TYPE_UNIT) return false

        val report = validation.validate(type, contentId)
        if (report.blockers) return false

        val threshold = maxOf(moderationPolicy.autoApproveThreshold(), domainFloor(report))
        if (report.score < threshold) return false

        val unit = contentUnits.findById(contentId).orElse(null) ?: return false
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

    private fun domainFloor(report: ValidationReport): Double {
        val codes = report.findings.map { it.code }
        return when {
            codes.any { it.startsWith("CURRICULUM") || it.startsWith("STRUCTURE") } -> 1.0
            codes.any { it.startsWith("ANSWER_KEY") } -> 0.995
            else -> 0.0
        }
    }

    private companion object {
        const val STATE_REVIEWED = "REVIEWED"
    }
}
