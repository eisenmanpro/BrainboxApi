package com.afrithecus.brainbox.api.subscription.web

/** Subscription payload matching the Android Subscription model. */
data class SubscriptionPayload(
    val userId: String,
    val tier: String,
    val status: String,
    val expiryDate: Long? = null,
    val amountPaid: Int = 0,
    val mpesaTransactionId: String? = null,
)
