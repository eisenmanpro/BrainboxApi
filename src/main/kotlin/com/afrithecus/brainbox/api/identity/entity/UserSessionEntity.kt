package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.identity.model.SessionRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Device session (doc 01 §5.2). Enforces max 3 student sessions per device and a
 * single session per device for teachers/parents.
 */
@Entity
@Table(name = "user_sessions")
class UserSessionEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "device_id", nullable = false, length = 128)
    var deviceId: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var role: SessionRole = SessionRole.STUDENT

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "last_active_at", nullable = false)
    var lastActiveAt: Instant = Instant.now()
}
