package com.afrithecus.brainbox.api.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One like/dislike per reader per article. */
@Entity
@Table(name = "news_votes")
class NewsVoteEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "news_id", nullable = false)
    var newsId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "vote_type", nullable = false, length = 16)
    var voteType: String = "UPVOTE"

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
