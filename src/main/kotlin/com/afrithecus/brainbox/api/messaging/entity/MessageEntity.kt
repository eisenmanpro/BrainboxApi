package com.afrithecus.brainbox.api.messaging.entity

import com.afrithecus.brainbox.api.messaging.model.Folder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One delivered message row (recipient inbox or sender sent copy). */
@Entity
@Table(name = "messages")
class MessageEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    /** Client message id (msg_<ts>_<userId>) shared by all fan-out copies. */
    @Column(name = "msg_group", nullable = false, length = 128)
    var msgGroup: String = ""

    /**
     * The conversation this row belongs to: the first message's [msgGroup]. A reply is saved
     * under the same root (instead of starting a new thread), so the client can group an inbox
     * by conversation and open the whole history.
     */
    @Column(name = "thread_root_group", length = 128)
    var threadRootGroup: String? = null

    /** The message this row answers, when it is a reply. */
    @Column(name = "parent_message_id")
    var parentMessageId: UUID? = null

    @Column(name = "sender_id", nullable = false)
    var senderId: UUID = UUID.randomUUID()

    @Column(name = "recipient_id")
    var recipientId: UUID? = null

    @Column(length = 255)
    var subject: String? = null

    @Column(nullable = false)
    var body: String = ""

    /** JSON array of attachment objects. */
    @Column(columnDefinition = "text")
    var attachments: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var folder: Folder = Folder.inbox

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false

    @Column(name = "read_at")
    var readAt: Instant? = null

    @Column(name = "intended_for_parent", nullable = false)
    var intendedForParent: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
