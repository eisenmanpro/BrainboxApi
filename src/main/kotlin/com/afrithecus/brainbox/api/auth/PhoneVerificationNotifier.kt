package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Out-of-band delivery of a phone-verification code (docs/phone_verification.md). Mirrors the
 * password-reset notifier and the FCM push sender: a provider seam selected by config, with a
 * logging implementation so the API boots and the flow is testable without credentials.
 */
interface PhoneVerificationNotifier {
    fun send(user: UserEntity, code: String)
}

/** One message body for every provider, so delivery channels cannot drift. */
internal fun phoneVerificationMessage(properties: PhoneVerificationProperties, code: String): String =
    "Your BrainBox verification code is " + code + ". It expires in " +
        properties.codeTtl.toMinutes() + " minutes. Do not share it."

/**
 * Development delivery: logs the code only when app.auth.phone-verification.log-otp is on, and
 * otherwise records that a code was generated. Production swaps to Africa's Talking or Beem.
 */
@Component
@ConditionalOnProperty(
    prefix = "app.auth.phone-verification",
    name = ["provider"],
    havingValue = "logging",
    matchIfMissing = true,
)
class LoggingPhoneVerificationNotifier(
    private val properties: PhoneVerificationProperties,
) : PhoneVerificationNotifier {

    override fun send(user: UserEntity, code: String) {
        if (properties.logOtp) {
            log.warn("Phone verification code for user {}: {}", user.id, code)
        } else {
            log.info("Phone verification code generated for user {} (logging provider; no SMS delivered)", user.id)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(LoggingPhoneVerificationNotifier::class.java)
    }
}
