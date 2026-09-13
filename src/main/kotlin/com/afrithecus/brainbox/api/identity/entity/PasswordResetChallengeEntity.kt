package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A password-reset challenge: a hashed one-time code and, once verified, a
 * short-lived single-use reset token (docs/ongoing/api_auth_changes.md).
 */
@Entity
@Table(name = "password_reset_challenges")
class PasswordResetChallengeEntity : BaseEntity() {

    @Column(nullable = false, length = 160)
    var identifier: String = ""

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "otp_hash", nullable = false, length = 64)
    var otpHash: String = ""

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(nullable = false)
    var verified: Boolean = false

    @Column(name = "reset_token_hash", length = 64)
    var resetTokenHash: String? = null

    @Column(name = "reset_token_expires_at")
    var resetTokenExpiresAt: Instant? = null

    @Column(nullable = false)
    var consumed: Boolean = false
}
