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
    val lastUpdated: Long,
)
