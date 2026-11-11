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
    /** The provider's application page, when the catalogue exposes one (F5-A6). */
    val applicationUrl: String? = null,
    /** Null means "this server does not track applications yet"; never invent a state. */
    val applicationStatus: String? = null,
)

/** One day in the seven-day activity series used by the achievements heatmap (F5-A4). */
data class DailyActivityPayload(
    val date: Long,
    val active: Boolean,
    val xpEarned: Int,
)

/** Server-computed monthly digest; replaces the deleted client-side fabrication (F5-A5). */
data class MonthlyDigestPayload(
    val month: String,
    val xpGained: Int,
    val contestsWon: Int,
    val strongestSubject: String? = null,
    val activeDays: Int,
    val recommendation: String,
)

/** One persisted freeze/grace credit (F5-A3). */
data class StreakCreditPayload(
    val date: Long,
    val kind: String,
)

/**
 * Streak credit request. ACTION is BUY or USE; KIND is FREEZE or GRACE. For USE,
 * [date] is an ISO local date (defaults to yesterday). A used credit covers that
 * missed day in the derived current/longest streak.
 */
data class StreakAdjustmentRequest(
    val action: String,
    val kind: String = "FREEZE",
    val date: String? = null,
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
    val contestsParticipated: Int,
    val contestsWon: Int,
    val rankPosition: Int,
    val totalStudentsRanked: Int,
    val topPercentile: Double,
    val masteryLevel: String = "NOVICE",
    val streakFreezes: Int = 0,
    val graceDays: Int = 0,
    val streakCredits: List<StreakCreditPayload> = emptyList(),
    val dailyActivity: List<DailyActivityPayload> = emptyList(),
    val monthlyDigest: MonthlyDigestPayload? = null,
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
