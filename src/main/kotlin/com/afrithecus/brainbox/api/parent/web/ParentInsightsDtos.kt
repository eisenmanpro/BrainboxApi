package com.afrithecus.brainbox.api.parent.web

/**
 * Parent dashboard payloads. Each one mirrors the Android model the dashboard already
 * renders (`models/ParentModels.kt`, `models/Models.kt`), so the app needs no shape change
 * to consume a real endpoint instead of the legacy call it used to make.
 */

/** Android `SubjectPerformance`. `color` is a client-side display colour. */
data class SubjectPerformancePayload(
    val subject: String,
    val score: Double,
    val color: Long = 0,
)

/** Android `ChildStats`. */
data class ChildStatsPayload(
    val avgScore: Int,
    val nationalRank: Int,
    val streakDays: Int,
    val totalXP: Int,
    val badges: List<String> = emptyList(),
)

/** Android `ParentAlert`: priority is CRITICAL|ACADEMIC|SOCIAL|BEHAVIORAL, category INFO|WARNING|SUCCESS|DANGER. */
data class ParentAlertPayload(
    val id: String,
    val title: String,
    val message: String,
    val priority: String,
    val category: String,
    val timestamp: Long,
    val isRead: Boolean = false,
)

/** Android `RecentActivity`; `type` is an `ActivityType` name. */
data class RecentActivityPayload(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val timestamp: Long,
    val status: String? = null,
)

/** Android `WeeklyPerformance`: seven day scores, oldest first, 0 when nothing was marked. */
data class WeeklyPerformancePayload(
    val scores: List<Int> = List(7) { 0 },
)

/** Android `UpcomingEvent` (only a title is rendered today). */
data class UpcomingEventPayload(
    val title: String,
    val date: Long = 0,
    val type: String = "ACADEMIC",
)

/** Android `HomeworkItem`; `status` is a `HomeworkStatus` name. */
data class HomeworkItemPayload(
    val id: String,
    val subject: String,
    val title: String,
    val dueDate: Long,
    val status: String,
    val grade: Int? = null,
    val maxGrade: Int? = null,
    val submissionUrl: String? = null,
)

/** Android `EngagementScore`. */
data class EngagementPayload(
    val weekly: Int,
    val monthly: Int,
    val factors: List<String> = emptyList(),
)

/** Android `CbcStrandRating`; `rating` is a `CbcRating` name. */
data class CbcStrandRatingPayload(
    val strandCode: String,
    val descriptor: String,
    val rating: String,
    val evidenceLink: String? = null,
)

/** Android `ParentRecommendation`: an action for the guardian, not the learner's feed. */
data class ParentRecommendationPayload(
    val id: String,
    val action: String,
    val reason: String,
    /** An Android route the dashboard can navigate to (message_centre, conference_scheduler, achievements). */
    val ctaRoute: String,
    /** HIGH | MEDIUM | LOW */
    val priority: String,
)

/** Android `TeacherMessage`: the guardian's view of a message about a child. */
data class ParentMessagePayload(
    val id: String,
    val fromTeacher: String,
    val subject: String,
    val message: String,
    val timestamp: Long,
    val isRead: Boolean,
)

/** Android `ParentSendMessageRequest`. */
data class SendParentMessageRequest(
    val childId: String,
    val message: String,
    val subject: String? = null,
)

/**
 * One child's line in the family comparison (`GET parent/family/comparison`).
 *
 * Every metric is null when the school holds no data for it rather than 0, so the
 * dashboard can say "no marks yet" instead of ranking a child on an invented zero.
 */
data class SiblingComparisonPayload(
    val childId: String,
    val name: String,
    val grade: String,
    val avatarUrl: String? = null,
    /** Mean of the child's counted gradebook percentages, 0..100. */
    val averageScore: Int? = null,
    /** Share of marked attendance sessions the child attended, 0..100. */
    val attendanceRate: Int? = null,
    /** This week's engagement score, the same number the dashboard gauge shows. */
    val engagementScore: Int? = null,
    val streakDays: Int = 0,
    val badges: Int = 0,
)

/** Android `FamilyComparison`: the guardian's linked children side by side. */
data class FamilyComparisonPayload(
    val children: List<SiblingComparisonPayload> = emptyList(),
    val generatedAt: Long = 0L,
)
