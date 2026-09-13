package com.afrithecus.brainbox.api.recommendation.web

/**
 * Recommendation rail payloads (doc 14 §3) matching the Android Recommendation
 * and RecommendationsResponse models exactly.
 */

data class RecommendationDto(
    val id: String,
    val type: String,
    val priority: String,
    val postId: String,
    val postTitle: String,
    val postSubject: String,
    val reason: String,
    val confidenceScore: Float,
    val imageUrl: String? = null,
)

data class RecommendationsResponseDto(
    val recommendations: List<RecommendationDto>,
    /** Trending for the caller's school; the client merges it (personalized wins). */
    val trending: List<RecommendationDto> = emptyList(),
    val lastUpdated: Long,
)

/**
 * Best-effort interaction telemetry (docs/ongoing/api_recommendations_changes.md).
 * The actor is derived from the token; userId is accepted for wire compatibility
 * but ignored.
 */
data class RecommendationInteractionRequest(
    val userId: String? = null,
    val postId: String = "",
    val interactionType: String = "",
    val timeSpentSeconds: Int? = null,
    val interactionScore: Double = 0.0,
    val timestamp: Long? = null,
)
