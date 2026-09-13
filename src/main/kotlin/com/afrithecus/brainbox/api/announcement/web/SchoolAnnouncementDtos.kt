package com.afrithecus.brainbox.api.announcement.web

/** Admin school announcements (docs/ongoing/open_gaps.md ANN-1). */
data class SchoolAnnouncementAnalyticsPayload(
    val schoolId: String,
    val totalAnnouncements: Int,
    val announcementsLast30Days: Int,
    val scheduledMeetings: Int,
    val latestPostedAt: Long? = null,
    val announcements: List<SchoolAnnouncementReachPayload> = emptyList(),
)

data class SchoolAnnouncementReachPayload(
    val announcementId: String,
    val title: String,
    val audience: String,
    val postedAt: Long,
    /**
     * Active school accounts the audience string resolves to. The admin studio
     * encodes audiences as "All"/"Custom" or "Grades: .. | Teachers: .. |
     * Classes: .."; anything unrecognised (legacy free text) falls back to the
     * whole active roster rather than reporting zero.
     */
    val estimatedReach: Int,
)

data class SchoolAnnouncementPayload(
    val announcementId: String,
    val title: String,
    val body: String,
    val audience: String,
    val postedBy: String = "",
    val postedAt: Long = 0,
    val scheduledMeetingDate: Long? = null,
    val meetingTitle: String? = null,
)
