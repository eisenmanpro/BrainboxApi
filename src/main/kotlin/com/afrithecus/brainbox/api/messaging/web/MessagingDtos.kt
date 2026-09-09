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
)

data class MessagePayload(
    val id: String,
    val senderId: String,
    val recipientId: String? = null,
    val subject: String? = null,
    val body: String,
    val attachments: List<AttachmentPayload>? = null,
    val folder: String,
    val isRead: Boolean,
    val readAt: Long? = null,
    val intendedForParent: Boolean,
    val createdAt: Long,
)

data class SchoolMemberPayload(
    val id: String,
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
)
