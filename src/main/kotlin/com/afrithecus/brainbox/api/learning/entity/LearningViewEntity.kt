package com.afrithecus.brainbox.api.learning.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Deduplicated view per (user, post, UTC day) (doc 03 §2.3). */
@Entity
@Table(name = "learning_views")
class LearningViewEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "post_id", nullable = false)
    var postId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "day_key", nullable = false, length = 16)
    var dayKey: String = ""

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
