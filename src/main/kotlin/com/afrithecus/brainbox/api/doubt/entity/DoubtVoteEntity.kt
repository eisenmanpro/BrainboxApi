package com.afrithecus.brainbox.api.doubt.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One voter's direction on a question or answer (doc 05 §3.8). */
@Entity
@Table(name = "doubt_votes")
class DoubtVoteEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "voter_id", nullable = false)
    var voterId: UUID = UUID.randomUUID()

    @Column(name = "target_type", nullable = false, length = 8)
    var targetType: String = "question"

    @Column(name = "target_id", nullable = false)
    var targetId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var direction: Int = 1

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
