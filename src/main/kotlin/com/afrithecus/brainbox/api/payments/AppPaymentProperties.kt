package com.afrithecus.brainbox.api.payments

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Payment relay configuration (doc 14 section 6). Everything the IntaSend
 * integration needs is here and environment-driven, so a deployment switches
 * from sandbox test keys to live keys without a code change:
 *
 *   APP_PAYMENTS_ENABLED=true
 *   APP_PAYMENTS_SANDBOX=false
 *   INTASEND_SECRET_KEY=...        (server-side only, never in the app)
 *   INTASEND_PUBLISHABLE_KEY=...
 *   PAYMENT_CALLBACK_CHALLENGE=... (compared on every webhook)
 *   PAYMENT_CALLBACK_URL=https://api.../payments/mpesa/callback
 *
 * Prices are server-authoritative; the client sends a tier and the server
 * verifies the amount matches it.
 */
@ConfigurationProperties(prefix = "app.payments")
data class AppPaymentProperties(
    val enabled: Boolean = false,
    val provider: String = "INTASEND",
    val currency: String = "KES",
    /** True uses IntaSend's sandbox host; false uses the live payment host. */
    val sandbox: Boolean = true,
    val callbackUrl: String = "",
    /** Shared secret IntaSend echoes in every webhook; must match. */
    val callbackChallenge: String = "",
    /** Optional explicit host override; blank derives from [sandbox]. */
    val baseUrl: String = "",
    val intasend: IntaSend = IntaSend(),
    val plans: Plans = Plans(),
) {

    /** IntaSend credentials. The secret key authorizes initiation and is server-only. */
    data class IntaSend(
        val secretKey: String = "",
        val publishableKey: String = "",
        val walletId: String = "",
    )

    /** The canonical price list (KES) and the paid period in days. */
    data class Plans(
        val explorer: Int = 100,
        val pro: Int = 150,
        val upgrade: Int = 50,
        val durationDays: Long = 30,
    )

    val resolvedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/').ifBlank { if (sandbox) SANDBOX_BASE_URL else LIVE_BASE_URL }

    companion object {
        const val SANDBOX_BASE_URL = "https://sandbox.intasend.com/api/v1"
        const val LIVE_BASE_URL = "https://payment.intasend.com/api/v1"
    }
}
