package com.afrithecus.brainbox.api.live.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One vote per user per poll; re-voting moves the vote. */
@Entity
@Table(name = "live_poll_votes")
class LivePollVoteEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "poll_id", nullable = false)
    var pollId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "option_index", nullable = false)
    var optionIndex: Int = 0

    @Column(name = "voted_at", nullable = false)
    var votedAt: Instant = Instant.now()
}
