package com.afrithecus.brainbox.api.messaging.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// ---------------------------------------------------------------------------
// Messaging payloads (doc 05 §2).
// ---------------------------------------------------------------------------

data class AttachmentPayload(
    val name: String? = null,
    val url: String? = null,
)

data class SendMessageRequest(
    @field:NotBlank
    val recipientId: String,
    @field:Size(max = 255)
    val subject: String? = null,
    @field:NotBlank
    val body: String,
    val attachments: List<AttachmentPayload>? = null,
    /** The message being replied to (a message id), so the reply joins its conversation. */
    val parentMessageId: String? = null,
    /** Composer category; unknown values are coerced to GENERAL. */
    @field:Size(max = 24)
    val category: String? = null,
    val priority: Boolean = false,
)

data class TeacherSendMessageRequest(
    @field:NotBlank
    val audienceType: String,
    val recipientId: String? = null,
    val audienceId: String? = null,
    @field:Size(max = 255)
    val subject: String? = null,
    @field:NotBlank
    val body: String,
    val attachments: List<AttachmentPayload>? = null,
    /** The message being replied to (a message id or conversation id), joins its thread. */
    val parentMessageId: String? = null,
    @field:Size(max = 24)
    val category: String? = null,
    val priority: Boolean = false,
)

data class MessagePayload(
    val id: String,
    val senderId: String,
    /**
     * Who sent it, so an inbox can render a sender without a second lookup. The platform's own
     * messages carry "Brainbox" and `senderRole = SYSTEM`.
     */
    val senderName: String? = null,
    val senderRole: String? = null,
    val recipientId: String? = null,
    val recipientName: String? = null,
    /** Present for the same reason as [senderRole]: a client renders both ends without a lookup. */
    val recipientRole: String? = null,
    val subject: String? = null,
    val body: String,
    val attachments: List<AttachmentPayload>? = null,
    val folder: String,
    val isRead: Boolean,
    val readAt: Long? = null,
    val intendedForParent: Boolean,
    val createdAt: Long,
    /** One send and its fan-out copies (kept for compatibility). */
    val msgGroup: String? = null,
    /** The conversation this row belongs to; the same value for every message in a thread. */
    val threadId: String = "",
    /** The message this row replies to, when it is a reply. */
    val parentMessageId: String? = null,
    /**
     * Delivery state for the **row's sender**: SENT (accepted), DELIVERED (recipient has it)
     * or READ (recipient opened it). A received row is DELIVERED until read, then READ.
     * Clients render this as the receipt under an outgoing message — it replaces the old
     * client-side "not submitted" derivation.
     */
    val status: String = "SENT",
    /** This copy's archive/flag state (per account; the sender and recipient each own a copy). */
    val isArchived: Boolean = false,
    val isFlagged: Boolean = false,
    /** Composer category and priority, persisted per copy (V38). */
    val category: String = "GENERAL",
    val isPriority: Boolean = false,
)

/** Archive state for one message copy the caller owns. */
data class MessageArchiveRequest(val archived: Boolean = true)

/** Flag state for one message copy the caller owns. */
data class MessageFlagRequest(val flagged: Boolean = true)

/**
 * One person the caller may message, with the relationship that justifies it.
 *
 * [relationship] is the human-readable reason the directory offers the row ("Your class",
 * "Teacher", "School admin", "Your grade", ...). The server computes eligibility; the client
 * only groups/filters this list, so it can never widen who it is allowed to address.
 */
data class MessageRecipientPayload(
    val id: String,
    val name: String,
    val role: String,
    val subRole: String? = null,
    val gradeLevel: String? = null,
    val classIds: List<String> = emptyList(),
    val classNames: List<String> = emptyList(),
    val relationship: String = "",
    /** Set on the admin-only cross-school directory row (the sender's own school is blank). */
    val schoolName: String? = null,
    /** Learner classes only: the guardian rows share the learner account (F9/N6). */
    val isGuardianAccount: Boolean = false,
)

data class SchoolMemberPayload(
    val id: String,
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
    /**
     * Staff sub-role (CTEACHER / GRADE_COORDINATOR / ICT_ADMIN). The project-approval
     * picker needs it to reach an ICT admin rather than an arbitrary colleague; it is
     * null for accounts that have no staff sub-role.
     */
    val subRole: String? = null,
)
