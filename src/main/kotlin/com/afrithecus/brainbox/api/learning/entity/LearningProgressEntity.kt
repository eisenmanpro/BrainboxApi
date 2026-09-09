package com.afrithecus.brainbox.api.learning.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Per-post learning progress (doc 03 §4.1). */
@Entity
@Table(name = "learning_progress")
class LearningProgressEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "post_id", nullable = false)
    var postId: UUID = UUID.randomUUID()

    @Column(name = "quiz_score", nullable = false)
    var quizScore: Int = -1

    @Column(name = "last_viewed_at", nullable = false)
    var lastViewedAt: Instant = Instant.now()

    @Column(nullable = false)
    var completed: Boolean = false

    @Column(name = "answers_json", columnDefinition = "text")
    var answersJson: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
