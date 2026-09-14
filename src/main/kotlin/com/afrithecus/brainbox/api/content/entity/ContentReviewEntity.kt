package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * One reviewer decision on one content version (Phase 7.4a). Unique per
 * (reviewer, content_type, content_id, content_version): re-deciding updates the
 * existing row rather than adding a second one. [weight] snapshots the reviewer's
 * trust weight at decision time so the outcome can be recomputed consistently.
 */
@Entity
@Table(name = "content_reviews")
class ContentReviewEntity : BaseEntity() {

    @Column(name = "content_type", nullable = false, length = 16)
    var contentType: String = ""

    @Column(name = "content_id", nullable = false)
    var contentId: UUID = UUID.randomUUID()

    @Column(name = "content_version", nullable = false)
    var contentVersion: Int = 1

    @Column(name = "reviewer_id", nullable = false)
    var reviewerId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 16)
    var decision: String = "APPROVE"

    @Column(name = "reason_tags", columnDefinition = "text")
    var reasonTags: String? = null

    @Column(columnDefinition = "text")
    var comment: String? = null

    @Column(nullable = false)
    var weight: Double = 1.0
}
