package com.afrithecus.brainbox.api.announcement.web

/** Announcement payloads matching teacher/models/TeacherModels.kt exactly. */

data class TeacherAnnouncementPayload(
    val id: String,
    val teacherId: String,
    /** Author display name; the learner surface shows "From <teacherName>". */
    val teacherName: String = "",
    val title: String,
    val content: String,
    val type: String = "NOTICE",
    val audience: String = "CLASS",
    val targetClassIds: List<String> = emptyList(),
    val targetGradeLevels: List<Int> = emptyList(),
    val targetStudentIds: List<String> = emptyList(),
    val isPriority: Boolean = false,
    val sentAt: Long = 0,
    val viewCount: Int = 0,
    val acknowledgementCount: Int = 0,
    val scheduledAt: Long? = null,
    val expiresAt: Long? = null,
)

data class StudentAcknowledgementStatusPayload(
    val studentId: String,
    val studentName: String,
    val viewedAt: Long? = null,
    val acknowledgedAt: Long? = null,
)

data class AnnouncementAnalyticsPayload(
    val announcementId: String,
    val views: Int,
    val acknowledgements: Int,
    val studentStatus: List<StudentAcknowledgementStatusPayload> = emptyList(),
    val isStale: Boolean = false,
)
