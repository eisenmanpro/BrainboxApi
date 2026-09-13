package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthMessagePayload
import com.afrithecus.brainbox.api.auth.web.VerifyOtpPayload
import com.afrithecus.brainbox.api.identity.entity.PasswordResetChallengeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.PasswordResetChallengeRepository
import com.afrithecus.brainbox.api.identity.repository.RefreshTokenRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import com.afrithecus.brainbox.api.security.AuthThrottle
import com.afrithecus.brainbox.api.security.TokenHash
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Password recovery (docs/ongoing/api_auth_changes.md): a neutral
 * forgot-password request, OTP verification that mints a short-lived single-use
 * reset token, and a reset that invalidates every existing session. No response
 * ever reveals whether an identifier exists or carries the OTP.
 */
@Service
class PasswordResetService(
    private val userRepository: UserRepository,
    private val teacherCodeRepository: TeacherCodeRepository,
    private val challengeRepository: PasswordResetChallengeRepository,
    private val refreshRepository: RefreshTokenRepository,
    private val sessionRepository: UserSessionRepository,
    private val notifier: PasswordResetNotifier,
    private val passwordEncoder: PasswordEncoder,
    private val authThrottle: AuthThrottle,
    private val clock: Clock,
) {

    @Transactional
    fun request(identifierRaw: String): AuthMessagePayload {
        val identifier = identifierRaw.trim()
        if (identifier.isNotEmpty()) {
            authThrottle.check(THROTTLE_PREFIX + identifier)
            val user = resolveUser(identifier)
            if (user != null && user.isActive) {
                // A newer request supersedes any older unconsumed challenge.
                val stale = challengeRepository.findAllByIdentifierAndConsumedFalse(identifier)
                if (stale.isNotEmpty()) {
                    stale.forEach { it.consumed = true }
                    challengeRepository.saveAll(stale)
                }
                val otp = newOtp()
                val challenge = PasswordResetChallengeEntity().apply {
                    this.identifier = identifier
                    userId = user.id
                    expiresAt = clock.instant().plus(OTP_TTL)
                    otpHash = hashOtp(id, otp)
                }
                challengeRepository.saveAndFlush(challenge)
                notifier.send(user, otp)
            }
        }
        // Neutral response: identical for known and unknown identifiers.
        return AuthMessagePayload(true, NEUTRAL_MESSAGE)
    }

    @Transactional
    fun verify(identifierRaw: String, otpRaw: String): VerifyOtpPayload {
        val identifier = identifierRaw.trim()
        authThrottle.check(THROTTLE_PREFIX + identifier)
        val challenge = challengeRepository.findTopByIdentifierAndConsumedFalseOrderByCreatedAtDesc(identifier)
        if (challenge == null || challenge.verified || !challenge.expiresAt.isAfter(clock.instant())) {
            authThrottle.recordFailure(THROTTLE_PREFIX + identifier)
            return invalidCode()
        }
        challenge.attempts += 1
        val otp = otpRaw.trim()
        val matches = otp.length == OTP_LENGTH && otp.all { it.isDigit() } &&
            TokenHash.sha256Hex(challenge.id.toString() + ":" + otp) == challenge.otpHash
        if (!matches || challenge.attempts > MAX_OTP_ATTEMPTS) {
            if (challenge.attempts > MAX_OTP_ATTEMPTS) challenge.consumed = true
            challengeRepository.saveAndFlush(challenge)
            authThrottle.recordFailure(THROTTLE_PREFIX + identifier)
            return invalidCode()
        }
        val resetToken = "rt_" + newToken()
        challenge.verified = true
        challenge.resetTokenHash = TokenHash.sha256Hex(resetToken)
        challenge.resetTokenExpiresAt = clock.instant().plus(RESET_TOKEN_TTL)
        challengeRepository.saveAndFlush(challenge)
        authThrottle.clear(THROTTLE_PREFIX + identifier)
        return VerifyOtpPayload(true, "Code verified.", resetToken)
    }

    @Transactional
    fun reset(resetTokenRaw: String, newPassword: String): AuthMessagePayload {
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            return AuthMessagePayload(false, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.")
        }
        val challenge = challengeRepository.findByResetTokenHashAndConsumedFalse(TokenHash.sha256Hex(resetTokenRaw.trim()))
            ?: return expired()
        val expiresAt = challenge.resetTokenExpiresAt
        if (!challenge.verified || expiresAt == null || !expiresAt.isAfter(clock.instant())) return expired()
        val user = userRepository.findById(challenge.userId).orElse(null) ?: return expired()
        user.passwordHash = passwordEncoder.encode(newPassword)
            ?: throw IllegalStateException("Password encoding failed")
        userRepository.save(user)
        revokeAllSessions(user.id)
        challenge.consumed = true
        challengeRepository.saveAndFlush(challenge)
        return AuthMessagePayload(true, "Password reset. You can now sign in.")
    }

    /** Drops challenges that expired more than a day ago (delivery already failed). */
    @Transactional
    fun pruneExpired() {
        challengeRepository.deleteByExpiresAtBefore(clock.instant().minus(Duration.ofDays(1)))
    }

    // ------------------------------------------------------------ internals

    private fun revokeAllSessions(userId: UUID) {
        val tokens = refreshRepository.findAllByUserIdAndRevokedFalse(userId)
        if (tokens.isNotEmpty()) {
            tokens.forEach { it.revoked = true }
            refreshRepository.saveAll(tokens)
        }
        val sessions = sessionRepository.findAllByUserIdAndIsActiveTrue(userId)
        if (sessions.isNotEmpty()) {
            sessions.forEach { it.isActive = false }
            sessionRepository.saveAll(sessions)
        }
    }

    private fun resolveUser(identifier: String): UserEntity? {
        userRepository.findByPhoneNumber(identifier)?.let { return it }
        if (identifier.contains('@')) {
            userRepository.findByEmail(identifier.lowercase())?.let { return it }
        }
        userRepository.findByStudentAdmissionNumber(identifier)?.let { return it }
        teacherCodeRepository.findByCodeAndActiveTrue(identifier.uppercase())?.let { code ->
            return userRepository.findById(code.teacherUserId).orElse(null)
        }
        return null
    }

    private fun newOtp(): String = "%06d".format(random.nextInt(1_000_000))

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashOtp(challengeId: UUID, otp: String): String = TokenHash.sha256Hex(challengeId.toString() + ":" + otp)

    private fun invalidCode() = VerifyOtpPayload(false, "Invalid or expired code. Request a new one.")

    private fun expired() = AuthMessagePayload(false, "Your reset link expired. Please start again.")

    private companion object {
        const val OTP_LENGTH = 6
        const val MAX_OTP_ATTEMPTS = 5
        const val MIN_PASSWORD_LENGTH = 6
        const val THROTTLE_PREFIX = "reset:"
        const val NEUTRAL_MESSAGE = "If that account exists, a reset code has been sent."
        val OTP_TTL: Duration = Duration.ofMinutes(10)
        val RESET_TOKEN_TTL: Duration = Duration.ofMinutes(10)
        val random = SecureRandom()
    }
}
