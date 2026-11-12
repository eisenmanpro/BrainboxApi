package com.afrithecus.brainbox.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Phone-verification settings (docs/phone_verification.md). Disabled by default: no code is sent
 * and the endpoints answer "not enabled" until an operator configures a provider. The provider
 * only delivers the SMS; the OTP lifecycle belongs to the API.
 *
 * Provider credentials come from the environment and are never logged:
 * AFRICASTALKING_USERNAME / AFRICASTALKING_API_KEY / AFRICASTALKING_SENDER_ID,
 * BEEM_API_KEY / BEEM_SECRET / BEEM_SENDER_ID.
 */
@ConfigurationProperties(prefix = "app.auth.phone-verification")
data class PhoneVerificationProperties(
    val enabled: Boolean = false,
    /** logging | africastalking | beem */
    val provider: String = "logging",
    /** Development only: log the generated code when the logging provider is active. */
    val logOtp: Boolean = false,
    /**
     * When true, an admin approval refuses a PENDING account whose phone is unverified.
     * Off by default: whether a verified phone is required is a product decision, and turning
     * this on must never block existing accounts by surprise.
     */
    val requireForApproval: Boolean = false,
    val codeTtl: Duration = Duration.ofMinutes(10),
    val resendCooldown: Duration = Duration.ofSeconds(60),
    val maxAttempts: Int = 5,
    val maxRequestsPerHour: Int = 5,
    /** Provider HTTP timeouts; an OTP SMS must not retry (a duplicate code costs money and confuses). */
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(5),
    val africastalking: AfricasTalking = AfricasTalking(),
    val beem: Beem = Beem(),
) {
    data class AfricasTalking(
        val username: String = "",
        val apiKey: String = "",
        val senderId: String = "",
        val baseUrl: String = "https://api.africastalking.com/version1/messaging",
    ) {
        val configured: Boolean get() = username.isNotBlank() && apiKey.isNotBlank()
    }

    data class Beem(
        val apiKey: String = "",
        val secret: String = "",
        val senderId: String = "",
        val baseUrl: String = "https://apisms.beem.africa/v1/send",
    ) {
        val configured: Boolean get() = apiKey.isNotBlank() && secret.isNotBlank()
    }
}
