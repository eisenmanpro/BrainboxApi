package com.afrithecus.brainbox.api.achievements.web

/** Achievement/reward payloads matching the Android models exactly. */

data class BadgePayload(
    val id: String,
    val title: String,
    val icon: String,
    val isUnlocked: Boolean,
    val iconRes: String? = null,
    val tier: String? = null,
    val cbcStrand: String? = null,
)

data class ScholarshipFlagPayload(
    val id: String,
    val name: String,
    val description: String,
    val criteria: String,
    val progress: Float,
    val isEligible: Boolean,
    val organization: String,
    val deadline: Long? = null,
)

data class UserAchievementsPayload(
    val userId: String,
    val level: Int,
    val levelTitle: String,
    val currentXP: Int,
    val xpToNextLevel: Int,
    val totalXP: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val badges: List<BadgePayload>,
    val completedChallenges: Int,
    val totalChallenges: Int,
    val contestsParticipated: Int,
    val contestsWon: Int,
    val rankPosition: Int,
    val totalStudentsRanked: Int,
    val topPercentile: Double,
    val masteryLevel: String = "NOVICE",
    val streakFreezes: Int = 0,
    val graceDays: Int = 0,
    val scholarshipFlags: List<ScholarshipFlagPayload> = emptyList(),
)

data class ContestHistoryEntryPayload(
    val contestId: String,
    val contestTitle: String,
    val subject: String,
    val yourScore: Double,
    val totalScore: Double,
    val rank: Int,
    val totalParticipants: Int,
    val date: Long,
    val prizeWon: String? = null,
    val certificateUrl: String? = null,
)

data class LeaderboardEntryPayload(
    val userId: String? = null,
    val rank: Int,
    val studentName: String,
    val schoolName: String,
    val score: Double,
    val avatarUrl: String? = null,
    val isCurrentUser: Boolean = false,
    val masteryLevel: String? = null,
)

data class LeaderboardResponsePayload(
    val entries: List<LeaderboardEntryPayload>,
    val userEntry: LeaderboardEntryPayload? = null,
)

data class MasteryNodePayload(
    val nodeId: String,
    val name: String,
    val cbcStrand: String? = null,
    val masteryPercent: Float,
    val isUnlocked: Boolean,
    val prerequisites: List<String> = emptyList(),
    val subject: String = "",
    val recommendedNextTopic: String? = null,
)

data class MasteryTreePayload(
    val nodes: List<MasteryNodePayload>,
    val rootNodeId: String,
    val recommendedPath: List<String> = emptyList(),
)

data class RewardItemPayload(
    val id: String,
    val title: String,
    val description: String,
    val xpCost: Int,
    val icon: String,
    val type: String,
    val isRedeemed: Boolean = false,
    val couponCode: String? = null,
)
