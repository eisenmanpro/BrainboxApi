package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.mail.TransactionalEmailSender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Out-of-band delivery of a password-reset code. The API response never carries the OTP.
 *
 * Delivery prefers the account's email address through the configured transactional transport
 * (Mailjet in production); when that transport is the development sink, the code is only logged and
 * only when app.auth.password-reset.log-otp is on. A delivery failure is swallowed on purpose: the
 * forgot-password response must stay neutral, and the user can simply request another code.
 */
interface PasswordResetNotifier {
    fun send(user: UserEntity, otp: String)
}

@Component
class ConfiguredPasswordResetNotifier(
    private val sender: TransactionalEmailSender,
    @Value("\${app.auth.password-reset.log-otp:false}") private val logOtp: Boolean,
) : PasswordResetNotifier {

    override fun send(user: UserEntity, otp: String) {
        val email = user.email?.takeIf { it.isNotBlank() }
        if (email != null && sender.configured) {
            val body = buildString {
                append("Hi ").append(user.name.trim().ifEmpty { "there" }).append(",\n\n")
                append("Your BrainBox password reset code is ").append(otp).append(".\n")
                append("It expires in 10 minutes. Do not share it.\n\n")
                append("If you did not ask to reset your password, ignore this email.\n\n")
                append("BrainBox")
            }
            runCatching { sender.send(email, "Your BrainBox password reset code", body) }
                .onFailure { log.warn("Password reset email delivery failed for user {}: {}", user.id, it.message) }
            return
        }
        if (logOtp) {
            log.warn("Password reset code for user {}: {}", user.id, otp)
        } else {
            log.info("Password reset code generated for user {} (no email on file / no transport configured)", user.id)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ConfiguredPasswordResetNotifier::class.java)
    }
}
