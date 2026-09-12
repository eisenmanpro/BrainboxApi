package com.afrithecus.brainbox.api.classchat.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A message posted to a class chat group (doc 04 §12.2). */
@Entity
@Table(name = "class_group_messages")
class ClassGroupMessageEntity : BaseEntity() {

    @Column(name = "group_id", nullable = false)
    var groupId: UUID = UUID.randomUUID()

    @Column(name = "sender_id", nullable = false)
    var senderId: UUID = UUID.randomUUID()

    @Column(name = "sender_name", nullable = false, length = 160)
    var senderName: String = ""

    @Column(name = "sender_role", nullable = false, length = 16)
    var senderRole: String = "TEACHER"

    @Column(nullable = false, columnDefinition = "text")
    var text: String = ""

    @Column(name = "is_pinned", nullable = false)
    var isPinned: Boolean = false

    @Column(name = "is_announcement", nullable = false)
    var isAnnouncement: Boolean = false

    @Column(name = "reply_to_id")
    var replyToId: UUID? = null

    /** Stable client id used to de-duplicate replayed offline sends. */
    @Column(name = "client_message_id", length = 128)
    var clientMessageId: String? = null

    /** JSON array string of MessageAttachment. */
    @Column(columnDefinition = "text")
    var attachments: String? = null
}
