package com.afrithecus.brainbox.api.user.web

/** User profile/progress payloads (doc 14 §3) matching the Android models. */

data class UserProfilePayload(
    val id: String,
    val name: String,
    val email: String,
    val avatarUrl: String,
    val subscriptionStatus: String,
    val subscriptionExpiry: String,
    val grade: Int = 9,
)

data class UserProgressPayload(
    val userId: String,
    val xp: Int,
    val level: Int,
    val streakDays: Int,
    val badges: List<String>,
    val progressTrend: List<Float> = emptyList(),
    val trendPercentage: Int = 0,
)
