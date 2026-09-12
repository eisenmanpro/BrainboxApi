package com.afrithecus.brainbox.api.dashboard.web

/**
 * Dashboard payloads (BACKEND_BLUEPRINT §2). Shapes match the Android dashboard
 * models exactly (models/Models.kt, models/DashboardActions.kt, models/Contest.kt).
 * Insights are server-computed and read-only on the client.
 */

data class AssignmentPayload(
    val id: String,
    val subject: String,
    val title: String,
    val dueDate: String,
    val isOverdue: Boolean = false,
)

data class ContestPayload(
    val id: String,
    val title: String,
    val subject: String,
    val grade: String,
    val status: String,
    val startTime: Long,
    val endTime: Long,
    val entryFee: Int,
    val prize: String,
    val registeredCount: Int,
    val maxParticipants: Int,
    val currentParticipantCount: Int? = null,
    val userRank: Int? = null,
    val userScore: Double? = null,
    val isUserRegistered: Boolean = false,
    val isSpacePreserved: Boolean = false,
    val thumbnailUrl: String? = null,
    val cbcStrands: List<String> = emptyList(),
)

data class QuickActionPayload(
    val id: String,
    val label: String,
    val iconName: String,
    val description: String,
    val route: String,
)

data class FocusZonePayload(
    val strugglingSubject: String,
    val recommendation: String,
    val xpBonus: Int,
)

data class NationalPulsePayload(
    val onlineCount: Int,
    val topSubject: String,
    val activePercentage: Int,
)

data class TeacherShoutoutPayload(
    val teacherName: String,
    val subject: String,
    val dueDate: String,
    val classAvg: Int,
    val userLast: Int,
    /** Unix ms the feedback was sent; 0 hides the timestamp on the client. */
    val sentAt: Long = 0,
)

data class PeerComparisonPayload(
    val percentile: Int,
    val targetPeer: String,
    val pointsAhead: Int,
)

data class StreakInfoPayload(
    val streakDays: Int,
    val nextMilestone: Int,
    val milestoneBadge: String,
)

data class WeeklyChallengePayload(
    val title: String,
    val current: Int,
    val target: Int,
    val reward: String,
)

data class FeaturedContestPayload(
    val title: String,
    val countdownText: String,
    val prize: String,
)

data class RecentActivityPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val timestamp: Long,
    val status: String? = null,
)

data class DashboardInsightsPayload(
    val focusZone: FocusZonePayload,
    val nationalPulse: NationalPulsePayload,
    val teacherShoutout: TeacherShoutoutPayload,
    val peerComparison: PeerComparisonPayload,
    val streakInfo: StreakInfoPayload,
    val weeklyChallenge: WeeklyChallengePayload,
    val socialNotifications: List<String>,
    val featuredContest: FeaturedContestPayload,
    val recentActivity: List<RecentActivityPayload>,
    val allBadges: List<String>,
    val xpMultiplier: Int,
    val xpMultiplierHours: Int,
    val xpMultiplierSource: String,
)
