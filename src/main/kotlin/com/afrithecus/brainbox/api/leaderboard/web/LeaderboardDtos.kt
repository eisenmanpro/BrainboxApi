package com.afrithecus.brainbox.api.leaderboard.web

/**
 * One admin leaderboard row. Shape follows doc 08 §7.1 but uses the Android
 * LeaderboardEntry field names (studentName/score) plus badgeCount.
 */
data class AdminLeaderboardEntryPayload(
    val rank: Int,
    val userId: String,
    val studentName: String,
    val schoolName: String,
    val gradeLevel: String? = null,
    val score: Double,
    val badgeCount: Int,
)
