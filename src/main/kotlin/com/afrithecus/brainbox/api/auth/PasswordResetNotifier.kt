package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Out-of-band delivery of a password-reset code. Real deployments plug in an SMS
 * or email provider; the API response never carries the OTP
 * (docs/ongoing/api_auth_changes.md).
 */
interface PasswordResetNotifier {
    fun send(user: UserEntity, otp: String)
}

/**
 * Development delivery: logs the code when app.auth.password-reset.log-otp is on
 * and otherwise only records that a code was generated. Swap for a provider in
 * production.
 */
@Component
class LoggingPasswordResetNotifier(
    @Value("\${app.auth.password-reset.log-otp:false}") private val logOtp: Boolean,
) : PasswordResetNotifier {

    override fun send(user: UserEntity, otp: String) {
        if (logOtp) {
            log.warn("Password reset code for user {}: {}", user.id, otp)
        } else {
            log.info("Password reset code generated for user {} (no delivery channel configured)", user.id)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LoggingPasswordResetNotifier::class.java)
    }
}
