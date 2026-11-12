package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.PhoneVerificationMessagePayload
import com.afrithecus.brainbox.api.auth.web.PhoneVerificationStatusPayload
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.entity.PhoneVerificationChallengeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.PhoneVerificationChallengeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.security.AuthThrottle
import com.afrithecus.brainbox.api.security.TokenHash
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Phone verification (docs/phone_verification.md): an authenticated account requests a 6-digit
 * code to its own phone number and confirms it. The code lifecycle mirrors password reset — hashed
 * at rest, single use, 10-minute TTL, attempt cap, per-account throttle — and the provider only
 * delivers the SMS, so the feature is dormant until an operator enables it.
 *
 * The approval pipeline stays the real gate. Enforcing "verified phone" at approval is opt-in
 * (require-for-approval) and off by default.
 */
@Service
class PhoneVerificationService(
    private val properties: PhoneVerificationProperties,
    private val userRepository: UserRepository,
    private val challengeRepository: PhoneVerificationChallengeRepository,
    private val notifier: PhoneVerificationNotifier,
    private val authThrottle: AuthThrottle,
    private val clock: Clock,
) {

    /** Safe to call while the feature is disabled: clients hide the card when [enabled] is false. */
    fun status(currentUser: CurrentUser): PhoneVerificationStatusPayload {
        val user = requireUser(currentUser)
        val now = clock.instant()
        val active = activeChallenge(user.id, now)
        val resendAfter = if (active == null) 0L else secondsUntilResend(active, now)
        return PhoneVerificationStatusPayload(
            enabled = properties.enabled,
            phoneNumber = user.phoneNumber,
            verified = user.phoneVerifiedAt != null,
            canRequest = properties.enabled && user.phoneVerifiedAt == null && resendAfter == 0L,
            resendAfterSeconds = resendAfter,
        )
    }

    @Transactional
    fun request(currentUser: CurrentUser): PhoneVerificationMessagePayload {
        ensureEnabled()
        val user = requireUser(currentUser)
        if (user.phoneVerifiedAt != null) {
            throw conflict("Your phone number is already verified.")
        }
        val phone = user.phoneNumber?.takeIf { it.isNotBlank() }
            ?: throw invalidArgument("Your account has no phone number to verify.")
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
        // Throttle this account's failures (bad codes, provider failures) before sending again.
        authThrottle.check(THROTTLE_PREFIX + user.id)

        // A newer request supersedes every older unconsumed challenge.
        val stale = challengeRepository.findAllByUserIdAndConsumedFalse(user.id)
        if (stale.isNotEmpty()) {
            stale.forEach {
                it.consumed = true
                it.consumedAt = now
            }
            challengeRepository.saveAll(stale)
        }

        val code = newCode()
        val challenge = PhoneVerificationChallengeEntity().apply {
            userId = user.id
            phoneNumber = phone
            sentAt = now
            expiresAt = now.plus(properties.codeTtl)
            codeHash = hashCode(id, code)
        }
        challengeRepository.saveAndFlush(challenge)
        try {
            notifier.send(user, code)
        } catch (error: Exception) {
            log.warn("Phone verification delivery failed for user {}: {}", user.id, error.message)
            // The transaction rolls back (no dangling challenge), but the failure still counts
            // against the account so a broken provider cannot be hammered in a tight loop.
            authThrottle.recordFailure(THROTTLE_PREFIX + user.id)
            throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "We couldn't send the verification code right now. Please try again.",
            )
        }
        return PhoneVerificationMessagePayload(
            success = true,
            message = "We sent a code to " + mask(phone) + ".",
            resendAfterSeconds = properties.resendCooldown.seconds.coerceAtLeast(0),
        )
    }

    @Transactional
    fun confirm(currentUser: CurrentUser, codeRaw: String): PhoneVerificationMessagePayload {
        ensureEnabled()
        val user = requireUser(currentUser)
        if (user.phoneVerifiedAt != null) {
            throw conflict("Your phone number is already verified.")
        }
        authThrottle.check(THROTTLE_PREFIX + user.id)
        val now = clock.instant()
        val code = codeRaw.trim()
        val challenge = activeChallenge(user.id, now)
        if (challenge == null || challenge.phoneNumber != user.phoneNumber ||
            code.length != CODE_LENGTH || !code.all { it.isDigit() }
        ) {
            authThrottle.recordFailure(THROTTLE_PREFIX + user.id)
            throw invalidArgument(INVALID_CODE)
        }
        challenge.attempts += 1
        val matches = TokenHash.sha256Hex(challenge.id.toString() + ":" + code) == challenge.codeHash
        if (!matches || challenge.attempts > properties.maxAttempts) {
            val tooMany = challenge.attempts > properties.maxAttempts
            if (tooMany) {
                challenge.consumed = true
                challenge.consumedAt = now
            }
            challengeRepository.saveAndFlush(challenge)
            authThrottle.recordFailure(THROTTLE_PREFIX + user.id)
            throw invalidArgument(if (tooMany) TOO_MANY_ATTEMPTS else INVALID_CODE)
        }
        challenge.consumed = true
        challenge.consumedAt = now
        challengeRepository.saveAndFlush(challenge)
        user.phoneVerifiedAt = now
        userRepository.save(user)
        authThrottle.clear(THROTTLE_PREFIX + user.id)
        return PhoneVerificationMessagePayload(success = true, message = "Phone number verified.")
    }

    /** Drops challenges that expired more than a day ago (delivery already failed). */
    @Transactional
    fun pruneExpired() {
        challengeRepository.deleteByExpiresAtBefore(clock.instant().minus(Duration.ofDays(1)))
    }

    // ------------------------------------------------------------ internals

    private fun ensureEnabled() {
        if (!properties.enabled) {
            throw ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "Phone verification is not enabled.")
        }
    }

    private fun requireUser(currentUser: CurrentUser): UserEntity =
        userRepository.findById(currentUser.userId).orElse(null)
            ?: throw ApiException(ApiErrorCode.UNAUTHORIZED, "Account no longer exists")

    private fun activeChallenge(userId: UUID, now: java.time.Instant): PhoneVerificationChallengeEntity? =
        challengeRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(userId)
            ?.takeIf { it.expiresAt.isAfter(now) }

    private fun secondsUntilResend(challenge: PhoneVerificationChallengeEntity, now: java.time.Instant): Long {
        val readyAt = challenge.createdAt.plus(properties.resendCooldown)
        return if (readyAt.isAfter(now)) Duration.between(now, readyAt).seconds + 1 else 0L
    }

    private fun tooMany(message: String, retryAfterSeconds: Long) =
        ApiException(ApiErrorCode.TOO_MANY_REQUESTS, message, mapOf("retryAfter" to retryAfterSeconds))

    private fun newCode(): String = "%06d".format(random.nextInt(1_000_000))

    private fun hashCode(challengeId: UUID, code: String): String =
        TokenHash.sha256Hex(challengeId.toString() + ":" + code)

    private fun mask(phone: String): String =
        if (phone.length <= 6) phone else phone.take(4) + "***" + phone.takeLast(2)

    private companion object {
        const val CODE_LENGTH = 6
        const val THROTTLE_PREFIX = "phone-verify:"
        const val INVALID_CODE = "Invalid or expired code. Request a new one."
        const val TOO_MANY_ATTEMPTS = "Too many incorrect attempts. Request a new code."
        const val ONE_HOUR_SECONDS = 3600L
        val random = SecureRandom()
        val log = LoggerFactory.getLogger(PhoneVerificationService::class.java)
    }
}
