package com.afrithecus.brainbox.api.cbc.entity

import com.afrithecus.brainbox.api.cbc.model.VoteType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One vote per user per project. */
@Entity
@Table(name = "cbc_project_votes")
class CbcProjectVoteEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "project_id", nullable = false)
    var projectId: UUID = UUID.randomUUID()

    /** Null for anonymous (guest) votes. */
    @Column(name = "user_id")
    var userId: UUID? = null

    /** Stable voter identity: "user:<uuid>" or "guest:<guestId>". */
    @Column(name = "voter_key", nullable = false, length = 80)
    var voterKey: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "vote_type", nullable = false, length = 16)
    var voteType: VoteType = VoteType.UPVOTE

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
