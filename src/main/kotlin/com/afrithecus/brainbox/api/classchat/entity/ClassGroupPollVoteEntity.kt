package com.afrithecus.brainbox.api.classchat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One vote per user per class-group poll; re-voting moves the vote. */
@Entity
@Table(name = "class_group_poll_votes")
class ClassGroupPollVoteEntity {

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
