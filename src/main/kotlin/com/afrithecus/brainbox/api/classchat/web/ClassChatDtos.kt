package com.afrithecus.brainbox.api.classchat.web

import jakarta.validation.constraints.NotBlank

/** Class-group chat payloads matching the Android ClassGroup/ClassGroupMessage models. */

data class ClassGroupPayload(
    val id: String,
    val name: String,
    val memberCount: Int,
    val teacherName: String,
    /** Empty for a GRADE-scoped group: legacy clients read this as a non-null String. */
    val classId: String = "",
    val description: String? = null,
    val unreadCount: Int = 0,
    val isMuted: Boolean = false,
    val isAnnouncementMode: Boolean = false,
    val lastMessage: String? = null,
    val lastMessageTime: Long? = null,
    /** CLASS | GRADE. */
    val scope: String = "CLASS",
    /** Canonical catalogue spelling; set for GRADE-scoped groups only. */
    val gradeLevel: String? = null,
    /** STUDENTS | TEACHERS | CLASS_TEACHERS | ALL. */
    val audience: String = "STUDENTS",
    val teacherId: String = "",
    val isFrozen: Boolean = false,
    val frozenReason: String? = null,
    val frozenAt: Long? = null,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    /** Display label the admin set when archiving ("Term 2 2026"). */
    val termLabel: String? = null,
    /** True for the creator: they may edit and delete their group. */
    val isCreator: Boolean = false,
    /** True when the caller is an explicit member row (creator excluded). */
    val isMember: Boolean = false,
    /** True when the caller may join this group now (audience + school + not frozen). */
    val canJoin: Boolean = false,
    /** True for the creator only; an ICT admin can moderate but never delete. */
    val canDelete: Boolean = false,
    /** True for an ICT admin of the group's school (freeze/archive/list). */
    val canModerate: Boolean = false,
    /** True for the two school-wide community groups Brainbox maintains. */
    val isSystem: Boolean = false,
    /** Explains a system group at the top of its thread ("This is ... community..."). */
    val systemNote: String? = null,
    /** Total messages in the thread (badge on the group card). */
    val messageCount: Int = 0,
)

/** One class a teacher may create a group for. */
data class GroupClassOptionPayload(
    val id: String,
    val name: String,
    val gradeLevel: String,
)

/**
 * What the create-group dialog may offer the caller: the classes they own (plus their
 * grade's classes when they are a coordinator) and the school's grade catalogue.
 */
data class GroupCreateOptionsPayload(
    val classes: List<GroupClassOptionPayload> = emptyList(),
    val grades: List<String> = emptyList(),
    val scopes: List<String> = listOf("CLASS", "GRADE"),
    val audiences: List<String> = listOf("STUDENTS", "TEACHERS", "CLASS_TEACHERS", "ALL"),
)

/** Body of the admin freeze/unfreeze action. A freeze requires a reason. */
data class AdminFreezeGroupRequest(
    val isFrozen: Boolean,
    val reason: String? = null,
)

/** Body of the admin term-archive action (reversible, read-only while archived). */
data class AdminArchiveGroupRequest(
    val isArchived: Boolean,
    val termLabel: String? = null,
    val reason: String? = null,
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

/**
 * A message body. `text` may be blank when [attachments] is not: an attachment-only
 * message (typically a file uploaded client-direct) is valid, and the service still
 * refuses a message with neither.
 */
data class SendMessageRequest(
    val text: String = "",
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

/**
 * Read-only gradebook contribution used by GET /teacher/class-groups/{id}/gradebook.
 * The row is backed by a graded homework submission, so `id` is an opaque
 * contribution id ("contrib_<submissionId>") that is NOT a gradebook entry id:
 * it is never accepted by PUT/DELETE teacher/gradebook/{entryId}. Homework
 * grades are edited through the homework grading endpoint instead (GB-1).
 */
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
