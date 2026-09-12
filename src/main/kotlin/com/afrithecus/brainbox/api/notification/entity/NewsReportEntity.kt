package com.afrithecus.brainbox.api.notification.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A moderation report on a news article. */
@Entity
@Table(name = "news_reports")
class NewsReportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "news_id", nullable = false)
    var newsId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 64)
    var reason: String = ""

    @Column(columnDefinition = "text")
    var details: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
