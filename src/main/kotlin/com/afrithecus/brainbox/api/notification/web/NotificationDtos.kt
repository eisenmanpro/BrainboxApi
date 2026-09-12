package com.afrithecus.brainbox.api.notification.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Notification payload matching the Android AppNotification model exactly. */
data class AppNotificationPayload(
    val id: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    val type: String,
    val isRead: Boolean = false,
    val actionRoute: String? = null,
    val actionLabel: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val urgency: String = "NORMAL",
    val priority: String = "NORMAL",
    val createdAt: Long = timestamp,
)

data class CreateNotificationRequest(
    @field:NotBlank @field:Size(max = 220) val title: String,
    @field:NotBlank val message: String,
    val type: String = "SYSTEM",
    val urgency: String = "NORMAL",
    val priority: String = "NORMAL",
    val actionRoute: String? = null,
    val actionLabel: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** News payload: the Android NewsItem fields plus doc 05 §6 extras. */
data class NewsItemPayload(
    val id: String,
    val title: String,
    val imageUrl: String,
    val timestamp: Long,
    val category: String,
    val content: String = "",
    val likes: Int = 0,
    val dislikes: Int = 0,
    val userLiked: Boolean = false,
    val userDisliked: Boolean = false,
    val commentCount: Int = 0,
    val author: String = "",
    val authorId: String? = null,
    val publishedAt: Long? = null,
    val status: String = "PUBLISHED",
    val tags: List<String> = emptyList(),
)

data class CreateNewsRequest(
    @field:NotBlank @field:Size(max = 240) val title: String,
    @field:NotBlank val content: String,
    val category: String = "News",
    @field:Size(max = 512) val imageUrl: String? = null,
    @field:Size(max = 160) val author: String? = null,
    val publishedAt: Long? = null,
    val status: String = "PUBLISHED",
    val tags: List<String> = emptyList(),
)

/** News engagement payloads (docs/ongoing/api_news_changes.md). */
data class NewsCommentPayload(
    val id: String,
    val userName: String,
    val userAvatar: String? = null,
    val content: String,
    val timestamp: Long = 0L,
)

data class NewsVoteRequest(@field:NotBlank val vote: String)

data class NewsVoteResponsePayload(val likes: Int, val dislikes: Int, val userVote: String)

data class NewsReportRequest(
    @field:NotBlank @field:Size(max = 64) val reason: String,
    @field:Size(max = 2000) val details: String? = null,
)

data class NewsReportResponsePayload(
    val reportId: String,
    val status: String = "RECEIVED",
    val submittedAt: Long,
)

data class AddNewsCommentRequest(
    @field:NotBlank @field:Size(max = 2000) val content: String,
    val authorName: String? = null,
    val authorAvatar: String? = null,
)
