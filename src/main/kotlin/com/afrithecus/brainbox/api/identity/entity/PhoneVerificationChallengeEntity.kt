package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One requested phone-verification code (docs/phone_verification.md). The code itself is never
 * stored: [codeHash] is SHA-256(challenge id + ":" + code), and the row is consumed on success,
 * on too many wrong attempts, on supersession or on pruning.
 */
@Entity
@Table(name = "phone_verification_challenges")
class PhoneVerificationChallengeEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    /** The account phone the code was sent to, so a changed number cannot reuse an old code. */
    @Column(name = "phone_number", nullable = false, length = 32)
    var phoneNumber: String = ""

    @Column(name = "code_hash", nullable = false, length = 64)
    var codeHash: String = ""

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant = Instant.now()

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()

    @Column(nullable = false)
    var consumed: Boolean = false

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null
}
