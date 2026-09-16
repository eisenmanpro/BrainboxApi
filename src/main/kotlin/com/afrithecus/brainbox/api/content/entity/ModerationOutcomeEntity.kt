package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The aggregated moderation state for one content version (Phase 7.4a). Exactly
 * one row per (content_type, content_id, content_version); [decidedAt] is set only
 * once the state resolves to REVIEWED or REJECTED.
 */
@Entity
@Table(name = "moderation_outcomes")
class ModerationOutcomeEntity : BaseEntity() {

    @Column(name = "content_type", nullable = false, length = 16)
    var contentType: String = ""

    @Column(name = "content_id", nullable = false)
    var contentId: UUID = UUID.randomUUID()

    @Column(name = "content_version", nullable = false)
    var contentVersion: Int = 1

    @Column(nullable = false, length = 16)
    var state: String = "UNREVIEWED"

    @Column(nullable = false)
    var approvals: Int = 0

    @Column(nullable = false)
    var rejections: Int = 0

    @Column(name = "weighted_approvals", nullable = false)
    var weightedApprovals: Double = 0.0

    @Column(name = "quorum_required", nullable = false)
    var quorumRequired: Int = 2

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    /** The human who resolved the version last; null for an unresolved or auto-approved outcome. */
    @Column(name = "reviewer_id")
    var reviewerId: UUID? = null

    /** True when the confidence gate approved this version with no human decision. */
    @Column(name = "auto_approved", nullable = false)
    var autoApproved: Boolean = false

    /** The validator confidence score that drove an auto-approval (0..1). */
    @Column(name = "confidence_score")
    var confidenceScore: Double? = null

    /**
     * O1 sampled audit: true when this auto-approval was deterministically picked
     * for human spot-checking (see AutoApprovalService and AuditSampling). False
     * for every human decision, so a sampled unit that a human then rejects leaves
     * the machine-audit listing.
     */
    @Column(name = "audit_sample", nullable = false)
    var auditSample: Boolean = false
}
