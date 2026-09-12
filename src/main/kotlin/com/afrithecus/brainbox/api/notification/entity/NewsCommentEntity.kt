package com.afrithecus.brainbox.api.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A reader comment on a news article. */
@Entity
@Table(name = "news_comments")
class NewsCommentEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "news_id", nullable = false)
    var newsId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "user_name", nullable = false, length = 160)
    var userName: String = ""

    @Column(name = "user_avatar", length = 512)
    var userAvatar: String? = null

    @Column(nullable = false, columnDefinition = "text")
    var content: String = ""

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
