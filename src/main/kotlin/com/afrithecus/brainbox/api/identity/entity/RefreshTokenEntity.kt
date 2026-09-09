package com.afrithecus.brainbox.api.identity.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Refresh token. Only the SHA-256 hex digest of the raw token is stored
 * (doc 11 §1). Tokens are grouped into [family] for rotation + reuse detection.
 */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "session_id")
    var sessionId: UUID? = null

    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String = ""

    @Column(nullable = false)
    var family: UUID = UUID.randomUUID()

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()

    @Column(nullable = false)
    var revoked: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
