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

/** One subscription history entry (doc 07 section 2.3). */
data class SubscriptionHistoryPayload(
    val id: String,
    val userId: String,
    val action: String,
    val tier: String,
    val amount: Int,
    val transactionId: String? = null,
    val timestamp: Long,
)
