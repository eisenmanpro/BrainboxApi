package com.afrithecus.brainbox.api.content.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * The weight-only reviewer trust ladder (Phase 7.4a). Brainbox staff never get a
 * row here; for everyone else [tier] and [weight] are derived from review volume
 * and agreement rate. Trust only ever changes how much a review counts, never
 * whether a teacher may review.
 */
@Entity
@Table(name = "reviewer_trust")
class ReviewerTrustEntity : BaseEntity() {

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var tier: Int = 0

    @Column(name = "reviews_count", nullable = false)
    var reviewsCount: Int = 0

    @Column(nullable = false)
    var agreements: Int = 0

    @Column(nullable = false)
    var weight: Double = 1.0
}
