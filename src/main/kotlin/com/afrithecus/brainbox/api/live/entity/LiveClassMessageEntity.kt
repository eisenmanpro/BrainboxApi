package com.afrithecus.brainbox.api.live.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A chat message in a live class; client_id makes an offline send replay-safe. */
@Entity
@Table(name = "live_class_messages")
class LiveClassMessageEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "user_name", nullable = false, length = 160)
    var userName: String = ""

    @Column(name = "user_role", nullable = false, length = 16)
    var userRole: String = "STUDENT"

    @Column(nullable = false, columnDefinition = "text")
    var message: String = ""

    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant = Instant.now()

    @Column(name = "is_pinned", nullable = false)
    var isPinned: Boolean = false
}
