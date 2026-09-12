package com.afrithecus.brainbox.api.announcement.web

/** Admin school announcements (docs/ongoing/open_gaps.md ANN-1). */
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
