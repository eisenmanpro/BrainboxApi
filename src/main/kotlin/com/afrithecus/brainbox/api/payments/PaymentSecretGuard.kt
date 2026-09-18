package com.afrithecus.brainbox.api.payments

import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to start with payments enabled but no usable credentials outside the
 * test profile. A node that boots live with a blank key would accept a payment
 * request and then fail the STK push, and a blank callback challenge would let
 * anyone post a forged COMPLETE webhook. Failing closed at startup is cheaper
 * than either.
 */
@Component
class PaymentSecretGuard(
    private val properties: AppPaymentProperties,
    private val environment: Environment,
) {

    @PostConstruct
    fun verify() {
        if (!properties.enabled) return
        if (environment.activeProfiles.contains(TEST_PROFILE)) return
        val problems = mutableListOf<String>()
        if (properties.intasend.secretKey.isBlank()) problems += "INTASEND_SECRET_KEY"
        if (properties.intasend.publishableKey.isBlank()) problems += "INTASEND_PUBLISHABLE_KEY"
        if (properties.callbackChallenge.isBlank()) problems += "PAYMENT_CALLBACK_CHALLENGE"
        if (!properties.sandbox && properties.callbackUrl.isBlank()) problems += "PAYMENT_CALLBACK_URL"
        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "Payments are enabled but required configuration is missing: " + problems.joinToString(", ") +
                    ". Set them before starting outside the test profile, or set APP_PAYMENTS_ENABLED=false.",
            )
        }
    }

    private companion object {
        const val TEST_PROFILE = "test"
    }
}
