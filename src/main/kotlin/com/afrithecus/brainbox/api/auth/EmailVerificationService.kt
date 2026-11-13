package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.EmailCodeMessagePayload
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.entity.EmailVerificationChallengeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.EmailVerificationChallengeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.mail.TransactionalEmailSender
import com.afrithecus.brainbox.api.security.AuthThrottle
import com.afrithecus.brainbox.api.security.TokenHash
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration

/**
 * Email verification for new staff accounts (gaps.md item 3): a 6-digit code is emailed at signup,
 * hashed at rest, single use, 10-minute TTL, attempt-capped and throttled per address. A teacher
 * cannot sign in until the address is confirmed; the approval gate still decides whether the
 * account gets any power afterwards.
 */
@Service
class EmailVerificationService(
    private val properties: EmailVerificationProperties,
    private val userRepository: UserRepository,
    private val challengeRepository: EmailVerificationChallengeRepository,
    private val sender: TransactionalEmailSender,
    private val authService: AuthService,
    private val authThrottle: AuthThrottle,
    private val clock: Clock,
) {

    /** Whether a signup must confirm its email address before it can be used. */
    val required: Boolean get() = properties.enabled

    /**
     * Issues the first code for a just-created account. Called inside the signup transaction: a
     * delivery failure rolls the account back, so nobody is left holding an unverifiable account.
     */
    @Transactional
    fun sendSignupCode(user: UserEntity) {
        if (!properties.enabled) return
        val email = user.email?.takeIf { it.isNotBlank() } ?: return
        val code = newCode()
        val now = clock.instant()
        supersede(user.id, now)
        val challenge = EmailVerificationChallengeEntity().apply {
            userId = user.id
            this.email = email
            expiresAt = now.plus(properties.codeTtl)
            codeHash = hash(id, code)
        }
        challengeRepository.saveAndFlush(challenge)
        deliver(email, user.name, code)
    }

    /**
     * Public resend. The response is identical for a known and an unknown address so it cannot be
     * used as an account oracle; throttling is keyed by the address itself.
     */
    @Transactional
    fun requestCode(identifierRaw: String): EmailCodeMessagePayload {
        ensureEnabled()
        val identifier = Emails.normalize(identifierRaw)
        if (identifier != null) {
            authThrottle.check(THROTTLE_PREFIX + identifier)
            val user = userRepository.findByEmailIgnoreCase(identifier)
            if (user != null && user.isActive && requiresVerification(user)) {
                val now = clock.instant()
                val active = activeChallenge(user.id, now)
                if (active != null) {
                    val retryAfter = secondsUntilResend(active, now)
                    if (retryAfter > 0) throw tooMany("A code was sent recently. Try again in " + retryAfter + " seconds.", retryAfter)
                }
                val requestedLastHour = challengeRepository.countByUserIdAndCreatedAtAfter(user.id, now.minus(Duration.ofHours(1)))
                if (requestedLastHour >= properties.maxRequestsPerHour) {
                    throw tooMany("Too many code requests. Try again later.", ONE_HOUR_SECONDS)
                }
                sendSignupCode(user)
            }
        }
        return EmailCodeMessagePayload(
            success = true,
            message = NEUTRAL_MESSAGE,
            resendAfterSeconds = properties.resendCooldown.seconds.coerceAtLeast(0),
        )
    }

    /**
     * Confirms the address and returns the normal auth response (with tokens). The account may still
     * be PENDING_VERIFICATION: the account-state gate keeps a pending account powerless until an
     * ICT admin approves it.
     */
    @Transactional
    fun verify(identifierRaw: String, codeRaw: String, deviceId: String?): AuthResponse {
        ensureEnabled()
        val identifier = Emails.normalize(identifierRaw) ?: throw invalidArgument(INVALID_CODE)
        val user = userRepository.findByEmailIgnoreCase(identifier) ?: throw invalidArgument(INVALID_CODE)
        if (!requiresVerification(user)) {
            throw conflict("That email address is already verified. Sign in instead.")
        }
        authThrottle.check(THROTTLE_PREFIX + identifier)
        val now = clock.instant()
        val challenge = activeChallenge(user.id, now) ?: throw invalidArgument(INVALID_CODE)
        challenge.attempts += 1
        val code = codeRaw.trim()
        val matches = code.length == CODE_LENGTH && code.all { it.isDigit() } &&
            TokenHash.sha256Hex(challenge.id.toString() + ":" + code) == challenge.codeHash
        if (!matches || challenge.attempts > properties.maxAttempts) {
            val exhausted = challenge.attempts > properties.maxAttempts
            if (exhausted) {
                challenge.consumed = true
                challenge.consumedAt = now
            }
            challengeRepository.saveAndFlush(challenge)
            authThrottle.recordFailure(THROTTLE_PREFIX + identifier)
            throw invalidArgument(if (exhausted) TOO_MANY_ATTEMPTS else INVALID_CODE)
        }
        challenge.consumed = true
        challenge.consumedAt = now
        challengeRepository.saveAndFlush(challenge)
        user.emailVerifiedAt = now
        user.emailVerificationRequired = false
        userRepository.save(user)
        authThrottle.clear(THROTTLE_PREFIX + identifier)
        return authService.issueAuthResponse(user, deviceId, includeTokens = true, message = "Email verified")
    }

    /** Drops challenges that expired more than a day ago (delivery already failed). */
    @Transactional
    fun pruneExpired() {
        challengeRepository.deleteByExpiresAtBefore(clock.instant().minus(Duration.ofDays(1)))
    }

    // ------------------------------------------------------------ internals

    private fun requiresVerification(user: UserEntity): Boolean =
        user.emailVerificationRequired && user.emailVerifiedAt == null

    private fun deliver(email: String, name: String, code: String) {
        val subject = "Your BrainBox verification code"
        val greeting = name.trim().ifEmpty { "there" }
        val body = buildString {
            append("Hi ").append(greeting).append(",\n\n")
            append("Your BrainBox verification code is ").append(code).append(".\n")
            append("It expires in ").append(properties.codeTtl.toMinutes()).append(" minutes. Do not share it.\n\n")
            append("If you did not create a BrainBox account, ignore this email.\n\n")
            append("BrainBox")
        }
        try {
            sender.send(email, subject, body)
        } catch (error: Exception) {
            log.warn("Email verification delivery failed for user {}: {}", email, error.message)
            throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "We couldn't send the verification email right now. Please try again.",
            )
        }
    }

    private fun ensureEnabled() {
        if (!properties.enabled) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "Email verification is not enabled.")
        }
    }

    private fun activeChallenge(userId: java.util.UUID, now: java.time.Instant): EmailVerificationChallengeEntity? =
        challengeRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(userId)
            ?.takeIf { it.expiresAt.isAfter(now) }

    private fun supersede(userId: java.util.UUID, now: java.time.Instant) {
        val stale = challengeRepository.findAllByUserIdAndConsumedFalse(userId)
        if (stale.isNotEmpty()) {
            stale.forEach {
                it.consumed = true
                it.consumedAt = now
            }
            challengeRepository.saveAll(stale)
        }
    }

    private fun secondsUntilResend(challenge: EmailVerificationChallengeEntity, now: java.time.Instant): Long {
        val readyAt = challenge.createdAt.plus(properties.resendCooldown)
        return if (readyAt.isAfter(now)) Duration.between(now, readyAt).seconds + 1 else 0L
    }

    private fun tooMany(message: String, retryAfterSeconds: Long) =
        ApiException(ApiErrorCode.TOO_MANY_REQUESTS, message, mapOf("retryAfter" to retryAfterSeconds))

    private fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    private fun hash(challengeId: java.util.UUID, code: String): String =
        TokenHash.sha256Hex(challengeId.toString() + ":" + code)

    private companion object {
        const val CODE_LENGTH = 6
        const val THROTTLE_PREFIX = "email-verify:"
        const val INVALID_CODE = "Invalid or expired code. Request a new one."
        const val TOO_MANY_ATTEMPTS = "Too many incorrect attempts. Request a new code."
        const val ONE_HOUR_SECONDS = 3600L
        const val NEUTRAL_MESSAGE = "If that account needs email verification, a new code is on its way."
        val random = SecureRandom()
        val log = LoggerFactory.getLogger(EmailVerificationService::class.java)
    }
}
