package com.afrithecus.brainbox.api.doubt.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One user's bookmark on a doubt question (doc 05 §3). */
@Entity
@Table(name = "doubt_bookmarks")
class DoubtBookmarkEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "question_id", nullable = false)
    var questionId: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
