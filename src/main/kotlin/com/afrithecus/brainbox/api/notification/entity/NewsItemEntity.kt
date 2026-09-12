package com.afrithecus.brainbox.api.notification.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.notification.model.NewsStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A news article shown in the app feed (doc 05 §6). */
@Entity
@Table(name = "news_items")
class NewsItemEntity : BaseEntity() {

    @Column(nullable = false, length = 240)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var content: String = ""

    @Column(name = "image_url", length = 512)
    var imageUrl: String? = null

    @Column(nullable = false, length = 64)
    var category: String = "General"

    /** Display attribution shown as the article byline; blank hides it. */
    @Column(length = 160)
    var author: String = ""

    @Column(name = "author_id")
    var authorId: UUID? = null

    @Column(name = "published_at")
    var publishedAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: NewsStatus = NewsStatus.PUBLISHED

    /** JSON array string of tags. */
    @Column(columnDefinition = "text")
    var tags: String? = null

    @Column(nullable = false)
    var likes: Int = 0

    @Column(nullable = false)
    var dislikes: Int = 0

    @Column(name = "comment_count", nullable = false)
    var commentCount: Int = 0
}
