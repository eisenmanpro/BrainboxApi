package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One emailed sign-in/confirmation code (gaps.md item 3). Mirrors the phone-verification challenge:
 * the code is hashed at rest, single use, TTL-bounded, attempt-capped and never returned by the API.
 */
@Entity
@Table(name = "email_verification_challenges")
class EmailVerificationChallengeEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 255)
    var email: String = ""

    @Column(name = "code_hash", nullable = false, length = 64)
    var codeHash: String = ""

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(nullable = false)
    var consumed: Boolean = false

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()
}
