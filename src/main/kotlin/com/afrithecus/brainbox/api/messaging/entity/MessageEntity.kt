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
