package com.afrithecus.brainbox.api.classchat.web

import jakarta.validation.constraints.NotBlank

/** Class-group chat payloads matching the Android ClassGroup/ClassGroupMessage models. */

data class ClassGroupPayload(
    val id: String,
    val name: String,
    val memberCount: Int,
    val teacherName: String,
    val classId: String,
    val description: String? = null,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val isAnnouncementMode: Boolean = false,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
)

data class PollOptionPayload(
    val id: String,
    val text: String,
    val votes: Int = 0,
    val isSelectedByMe: Boolean = false,
)

data class MeetingSlotPayload(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val isAvailable: Boolean = true,
    val attendeeId: String? = null,
)

data class MessageAttachmentPayload(
    val url: String,
    val type: String,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null,
    val pollOptions: List<PollOptionPayload>? = null,
    val meetingSlots: List<MeetingSlotPayload>? = null,
)

data class ClassGroupMessagePayload(
    val id: String,
    val groupId: String,
    val senderId: String = "",
    val senderName: String,
    val senderRole: String,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val attachments: List<MessageAttachmentPayload> = emptyList(),
    val replyToId: String? = null,
    val isAnnouncement: Boolean = false,
)

data class SendMessageRequest(
    @field:NotBlank val text: String,
    val attachments: List<MessageAttachmentPayload>? = null,
)

/** TeacherClass shape used by GET /teacher/class-groups/{id}/teachers. */
data class GroupTeacherPayload(
    val id: String,
    val name: String,
    val grade: Int,
    val section: String? = null,
    val subject: String,
    val teacherId: String,
    val teacherName: String,
    val schoolId: String,
    val studentCount: Int = 0,
    val createdAt: Long,
    val isActive: Boolean = true,
)

/** GradebookEntry shape used by GET /teacher/class-groups/{id}/gradebook. */
data class GradebookContributionPayload(
    val id: String,
    val classId: String,
    val teacherId: String,
    val assessmentId: String,
    val assessmentType: String,
    val studentId: String,
    val studentName: String,
    val rawScore: Int,
    val maxScore: Int,
    val percentage: Int,
    val assessmentTitle: String? = null,
    val cbcStrandTag: String? = null,
    val teacherNote: String? = null,
    val gradedAt: Long,
)
