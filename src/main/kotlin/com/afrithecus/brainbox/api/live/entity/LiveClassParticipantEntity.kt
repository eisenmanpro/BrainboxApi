package com.afrithecus.brainbox.api.live.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A host-roster row for a live class; mute/promote/kick update it in place. */
@Entity
@Table(name = "live_class_participants")
class LiveClassParticipantEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "user_name", nullable = false, length = 160)
    var userName: String = ""

    @Column(nullable = false, length = 16)
    var role: String = "STUDENT"

    @Column(name = "is_muted", nullable = false)
    var isMuted: Boolean = false

    @Column(name = "join_time", nullable = false)
    var joinTime: Instant = Instant.now()

    @Column(name = "is_removed", nullable = false)
    var isRemoved: Boolean = false
}
