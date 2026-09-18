package com.afrithecus.brainbox.api.payments

/** One STK push to initiate, provider-neutral. */
data class StkPushCommand(
    val phoneNumber: String,
    val amount: Int,
    val currency: String,
    /** Our transaction id, echoed by the provider so a webhook can find the row. */
    val reference: String,
    val narrative: String,
    val name: String? = null,
    val email: String? = null,
)

/** The provider's answer to an STK push: an invoice reference, or a reason. */
sealed interface StkPushOutcome {
    data class Accepted(val providerReference: String, val providerState: String) : StkPushOutcome
    data class Rejected(val message: String) : StkPushOutcome
}

/** A provider status poll result; [state] is one of [PaymentStates]. */
data class GatewayPaymentState(val state: String, val failedReason: String? = null)

/**
 * The payment provider seam. The router/service depends on this, not on IntaSend,
 * so tests run against a fake and a deployment swaps host and keys by
 * configuration. IntaSend is the only implementation.
 */
interface PaymentGateway {
    val name: String
    fun stkPush(command: StkPushCommand): StkPushOutcome
    fun status(providerReference: String): GatewayPaymentState
}

/** IntaSend webhook/invoice state vocabulary. */
object PaymentStates {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val COMPLETE = "COMPLETE"
    const val FAILED = "FAILED"
}
