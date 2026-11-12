package com.afrithecus.brainbox.api.sms

import com.afrithecus.brainbox.api.auth.PhoneVerificationProperties
import tools.jackson.databind.ObjectMapper

/**
 * Transport seam shared by phone-verification OTP and the attendance SMS channel (P3.1 in
 * docs/sms_and_attendance_notifications_plan.md). A gateway only delivers a message that the
 * caller already composed and decided to pay for; it owns no retry and no consent/budget policy.
 *
 * Implementations throw [IllegalStateException] when the provider did not accept the message, so
 * callers can persist an honest failure instead of claiming delivery. A provider that answers
 * HTTP 2xx with an error body (HostPinnacle) must still fail here.
 */
interface SmsGateway {

    /** Stable provider key, also the value of the provider configuration fields. */
    val provider: String

    /**
     * Sends [message] to a canonical Kenyan phone number ("0712345678", see PhoneNumbers).
     * Returns the provider's message id when it supplies one, otherwise null.
     */
    fun send(to: String, message: String): String?
}

/** Builds the same gateway instances for both features from the one credential block. */
object SmsGatewayFactory {

    fun forProvider(
        provider: String,
        properties: PhoneVerificationProperties,
        mapper: ObjectMapper,
    ): SmsGateway = when (provider.trim().lowercase()) {
        "hostpinnacle" -> HostpinnacleSmsGateway(
            properties.hostpinnacle,
            properties.connectTimeout,
            properties.requestTimeout,
            mapper,
        )
        "africastalking" -> AfricasTalkingSmsGateway(
            properties.africastalking,
            properties.connectTimeout,
            properties.requestTimeout,
            mapper,
        )
        "beem" -> BeemSmsGateway(
            properties.beem,
            properties.connectTimeout,
            properties.requestTimeout,
            mapper,
        )
        "logging" -> LoggingSmsGateway()
        else -> throw IllegalStateException("Unknown SMS provider: " + provider)
    }
}
