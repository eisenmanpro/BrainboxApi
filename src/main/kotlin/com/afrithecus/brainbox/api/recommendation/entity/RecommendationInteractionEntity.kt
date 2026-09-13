package com.afrithecus.brainbox.api.recommendation.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One learner interaction with a learning post (docs/ongoing/api_recommendations_changes.md).
 * Telemetry is best effort; (user, post, occurredAt) is unique so a replay is a no-op.
 */
@Entity
@Table(name = "recommendation_interactions")
class RecommendationInteractionEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "post_id", nullable = false)
    var postId: UUID = UUID.randomUUID()

    @Column(name = "interaction_type", nullable = false, length = 32)
    var interactionType: String = "view"

    @Column(name = "time_spent_seconds")
    var timeSpentSeconds: Int? = null

    @Column(name = "interaction_score", nullable = false)
    var interactionScore: Double = 0.0

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.now()

    @Column(name = "school_id")
    var schoolId: UUID? = null
}
