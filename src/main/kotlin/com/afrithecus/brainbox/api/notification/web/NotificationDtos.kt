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
    val authorId: String? = null,
    val publishedAt: Long? = null,
    val status: String = "PUBLISHED",
    val tags: List<String> = emptyList(),
)

data class CreateNewsRequest(
    @field:NotBlank @field:Size(max = 240) val title: String,
    @field:NotBlank val content: String,
    val imageUrl: String? = null,
    val category: String = "General",
    val publishedAt: Long? = null,
    val status: String = "PUBLISHED",
    val tags: List<String> = emptyList(),
)
