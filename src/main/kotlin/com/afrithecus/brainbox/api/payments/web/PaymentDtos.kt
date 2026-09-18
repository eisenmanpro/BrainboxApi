package com.afrithecus.brainbox.api.payments.web

import com.afrithecus.brainbox.api.subscription.web.SubscriptionPayload

/** POST /payments/stk-push response (doc 14 section 6). */
data class StkPushResponse(
    val success: Boolean,
    val message: String,
    val transactionId: String? = null,
    val subscription: SubscriptionPayload? = null,
)

/**
 * POST /payments/query tri-state (doc 14 section 6): success with a subscription
 * means done; success without one means still pending; failure is definitive.
 */
data class PaymentQueryResponse(
    val success: Boolean,
    val message: String? = null,
    val transactionId: String? = null,
    val subscription: SubscriptionPayload? = null,
)

/** Acknowledgement body for an IntaSend webhook. */
data class PaymentCallbackAck(
    val received: Boolean = true,
    val message: String? = null,
)
