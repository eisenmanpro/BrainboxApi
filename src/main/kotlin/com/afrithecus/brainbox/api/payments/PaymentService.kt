package com.afrithecus.brainbox.api.payments

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.payments.entity.PaymentTransactionEntity
import com.afrithecus.brainbox.api.payments.repository.PaymentTransactionRepository
import com.afrithecus.brainbox.api.payments.web.PaymentCallbackAck
import com.afrithecus.brainbox.api.payments.web.PaymentQueryResponse
import com.afrithecus.brainbox.api.payments.web.StkPushResponse
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import com.afrithecus.brainbox.api.subscription.SubscriptionView
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionHistoryEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionHistoryRepository
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import com.afrithecus.brainbox.api.subscription.web.SubscriptionPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * M-Pesa subscription payments relayed through IntaSend (doc 14 section 6,
 * doc 07 section 3). The server is authoritative for price and tier, holds the
 * IntaSend secret key, and is the only place a subscription is activated.
 *
 * Idempotency: an STK push may be retried with the same clientRequestId and a
 * webhook may be delivered many times. A transaction activates at most once
 * (guarded by its SUCCESS status), so a replayed COMPLETE never extends expiry
 * twice or double-charges.
 */
@Service
class PaymentService(
    private val properties: AppPaymentProperties,
    private val gateway: PaymentGateway,
    private val transactions: PaymentTransactionRepository,
    private val historyRepository: SubscriptionHistoryRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionService: SubscriptionService,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional
    fun stkPush(
        actor: CurrentUser,
        userIdRaw: String,
        amount: Int,
        phoneRaw: String,
        tierRaw: String,
        clientRequestId: String?,
    ): StkPushResponse {
        val userId = resolveSelf(actor, userIdRaw)
        val user = userRepository.findById(userId).orElse(null) ?: throw notFound("User not found")
        if (user.role != Role.STUDENT) {
            throw invalidArgument("Only student subscriptions are paid for here")
        }
        val tier = parseTier(tierRaw)
        val phone = normalizePhone(phoneRaw)
        validateAmount(tier, amount, subscriptionService.view(userId))

        val requestId = clientRequestId?.trim()?.takeIf { it.isNotEmpty() }
        if (requestId != null) {
            transactions.findByUserIdAndClientRequestId(userId, requestId)?.let { existing ->
                return StkPushResponse(
                    success = existing.status != PaymentStatus.FAILED.name,
                    message = "This request was already received",
                    transactionId = existing.id.toString(),
                    subscription = null,
                )
            }
        }

        val row = transactions.save(
            PaymentTransactionEntity().apply {
                this.userId = userId
                this.tier = tier.name
                this.amount = amount
                currency = properties.currency
                phoneNumber = phone
                provider = gateway.name
                status = PaymentStatus.PENDING.name
                this.clientRequestId = requestId
            }
        )

        if (!properties.enabled) {
            fail(row, "Payments are disabled on this deployment")
            return StkPushResponse(false, "Payments are temporarily unavailable", row.id.toString(), null)
        }

        return when (val outcome = gateway.stkPush(
            StkPushCommand(
                phoneNumber = phone,
                amount = amount,
                currency = properties.currency,
                reference = row.id.toString(),
                narrative = "BrainBox " + tier.name + " subscription",
                name = user.name,
                email = user.email,
            )
        )) {
            is StkPushOutcome.Accepted -> {
                row.provider = gateway.name
                row.providerRef = outcome.providerReference
                row.status = PaymentStatus.PENDING.name
                transactions.save(row)
                StkPushResponse(true, "STK push sent to " + phone, row.id.toString(), null)
            }
            is StkPushOutcome.Rejected -> {
                fail(row, outcome.message)
                StkPushResponse(false, outcome.message, row.id.toString(), null)
            }
        }
    }

    @Transactional
    fun query(actor: CurrentUser, transactionIdRaw: String): PaymentQueryResponse {
        val row = findTransaction(transactionIdRaw)
        requireOwnerOrAdmin(actor, row)
        val id = row.id.toString()

        if (row.status == PaymentStatus.SUCCESS.name) {
            return PaymentQueryResponse(true, "Payment successful", id, toSubscriptionPayload(subscriptionService.view(row.userId)))
        }
        if (row.status == PaymentStatus.FAILED.name || row.status == PaymentStatus.CANCELLED.name) {
            return PaymentQueryResponse(false, row.failureReason ?: "Payment failed", id, null)
        }

        // A webhook may be delayed or blocked; poll the provider so the client
        // still resolves within its ~90s window.
        if (properties.enabled && !row.providerRef.isNullOrBlank()) {
            val state = runCatching { gateway.status(row.providerRef!!) }.getOrNull()
            if (state != null) {
                when (state.state.uppercase()) {
                    PaymentStates.COMPLETE -> {
                        val view = activate(row)
                        return PaymentQueryResponse(true, "Payment successful", id, toSubscriptionPayload(view))
                    }
                    PaymentStates.FAILED -> {
                        fail(row, state.failedReason)
                        return PaymentQueryResponse(false, row.failureReason, id, null)
                    }
                }
            }
        }
        return PaymentQueryResponse(true, "Waiting for M-Pesa confirmation", id, null)
    }

    /**
     * IntaSend webhook. Authenticated only by the shared challenge: the endpoint
     * is public because IntaSend cannot hold a bearer token, so a missing or
     * mismatched challenge is rejected outright.
     */
    @Transactional
    fun handleCallback(payload: JsonNode): PaymentCallbackAck {
        val challenge = payload.get("challenge")?.asString()
            ?: payload.get("invoice")?.get("challenge")?.asString()
        if (!challengeMatches(challenge)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Invalid payment callback challenge")
        }
        val invoiceId = payload.get("invoice_id")?.asString()
            ?: payload.get("invoice")?.get("invoice_id")?.asString()
            ?: throw invalidArgument("callback has no invoice_id")
        val state = (
            payload.get("state")?.asString()
                ?: payload.get("invoice")?.get("state")?.asString()
                ?: ""
            ).uppercase()

        val row = transactions.findByProviderRef(invoiceId) ?: throw notFound("Unknown payment reference")
        if (row.status == PaymentStatus.SUCCESS.name) {
            // Idempotent: the same confirmation retried must not extend expiry again.
            return PaymentCallbackAck(true, "Already applied")
        }
        when (state) {
            PaymentStates.COMPLETE -> activate(row)
            PaymentStates.FAILED -> {
                val reason = payload.get("failed_reason")?.asString()
                    ?: payload.get("invoice")?.get("failed_reason")?.asString()
                fail(row, reason)
            }
            else -> {
                // PENDING / PROCESSING: record that we saw it; stay pending.
                row.status = PaymentStatus.PROCESSING.name
                transactions.save(row)
            }
        }
        return PaymentCallbackAck(true, state)
    }

    // ------------------------------------------------------------ activation

    /** Applies a confirmed payment to the subscription exactly once. */
    private fun activate(row: PaymentTransactionEntity): SubscriptionView {
        if (row.status == PaymentStatus.SUCCESS.name) return subscriptionService.view(row.userId)
        val subscription = ensure(row.userId)
        val now = clock.instant()
        val plan = properties.plans.durationDays
        val targetTier = runCatching { SubscriptionTier.valueOf(row.tier) }.getOrDefault(SubscriptionTier.EXPLORER)
        val wasBase = subscription.tier == SubscriptionTier.BASE || subscription.status == SubscriptionStatus.NONE
        val isUpgrade = subscription.tier == SubscriptionTier.EXPLORER &&
            targetTier == SubscriptionTier.PRO && row.amount == properties.plans.upgrade
        val action: String
        if (isUpgrade) {
            // An upgrade to Pro preserves the Explorer expiry (doc 07 section 3.4).
            subscription.tier = SubscriptionTier.PRO
            subscription.status = SubscriptionStatus.ACTIVE
            if (subscription.expiryDate == null || subscription.expiryDate!!.isBefore(now)) {
                subscription.expiryDate = now.plus(Duration.ofDays(plan))
            }
            action = "UPGRADED"
        } else {
            val base = subscription.expiryDate?.takeIf { it.isAfter(now) } ?: now
            subscription.tier = targetTier
            subscription.status = SubscriptionStatus.ACTIVE
            subscription.expiryDate = base.plus(Duration.ofDays(plan))
            action = if (wasBase) "CREATED" else "RENEWED"
        }
        subscription.totalPaid = subscription.totalPaid + row.amount
        subscription.mpesaTransactionId = row.providerRef ?: row.id.toString()
        subscriptionRepository.save(subscription)

        row.status = PaymentStatus.SUCCESS.name
        row.failureReason = null
        transactions.save(row)
        historyRepository.save(
            SubscriptionHistoryEntity().apply {
                userId = row.userId
                this.action = action
                tier = subscription.tier.name
                amount = row.amount
                transactionId = row.providerRef ?: row.id.toString()
            }
        )
        return subscriptionService.view(row.userId)
    }

    private fun ensure(userId: UUID): SubscriptionEntity =
        subscriptionRepository.findByUserId(userId) ?: subscriptionRepository.save(
            SubscriptionEntity().apply {
                this.userId = userId
                status = SubscriptionStatus.NONE
                tier = SubscriptionTier.BASE
            }
        )

    private fun fail(row: PaymentTransactionEntity, reason: String?) {
        if (row.status == PaymentStatus.SUCCESS.name) return
        row.status = PaymentStatus.FAILED.name
        row.failureReason = reason ?: "Payment failed"
        transactions.save(row)
    }

    // ------------------------------------------------------------- validation

    private fun validateAmount(tier: SubscriptionTier, amount: Int, current: SubscriptionView) {
        val plans = properties.plans
        if (tier == SubscriptionTier.EXPLORER) {
            if (amount != plans.explorer) throw invalidArgument("Explorer costs " + plans.explorer + " KES")
            return
        }
        if (amount == plans.pro) return
        if (amount == plans.upgrade) {
            // The upgrade price is valid only for a current Explorer holder.
            if (current.tier != SubscriptionTier.EXPLORER.name) {
                throw invalidArgument(
                    "The " + plans.upgrade + " KES price is only the Explorer-to-Pro upgrade; Pro costs " + plans.pro + " KES",
                )
            }
            return
        }
        throw invalidArgument("Pro costs " + plans.pro + " KES (or " + plans.upgrade + " to upgrade from Explorer)")
    }

    private fun parseTier(raw: String): SubscriptionTier {
        val tier = runCatching { SubscriptionTier.valueOf(raw.trim().uppercase()) }.getOrNull()
        if (tier != SubscriptionTier.EXPLORER && tier != SubscriptionTier.PRO) {
            throw invalidArgument("tier must be EXPLORER or PRO")
        }
        return tier
    }

    /** Kenyan M-Pesa number, normalised to 254[17]XXXXXXXX (doc 14 section 6). */
    private fun normalizePhone(raw: String): String {
        val digits = raw.trim().filter { it.isDigit() }
        val normalized = when {
            digits.length == 12 && digits.startsWith("254") -> digits
            digits.length == 10 && digits.startsWith("0") -> "254" + digits.substring(1)
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254" + digits
            else -> throw invalidArgument("Enter a valid Kenyan phone number, for example 0712345678")
        }
        if (normalized[3] != '7' && normalized[3] != '1') {
            throw invalidArgument("Enter a valid Kenyan phone number, for example 0712345678")
        }
        return normalized
    }

    // ---------------------------------------------------------------- access

    private fun resolveSelf(actor: CurrentUser, userIdRaw: String): UUID {
        val requested = runCatching { UUID.fromString(userIdRaw.trim()) }.getOrNull()
            ?: throw invalidArgument("userId is not a valid identifier")
        if (requested != actor.userId && actor.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "You can only pay for your own subscription")
        }
        return requested
    }

    private fun requireOwnerOrAdmin(actor: CurrentUser, row: PaymentTransactionEntity) {
        if (row.userId != actor.userId && actor.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your payment")
        }
    }

    private fun findTransaction(raw: String): PaymentTransactionEntity {
        val id = runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("transactionId is not a valid identifier")
        return transactions.findById(id).orElse(null) ?: throw notFound("Payment not found")
    }

    private fun challengeMatches(provided: String?): Boolean {
        val expected = properties.callbackChallenge
        if (expected.isBlank() || provided.isNullOrBlank()) return false
        return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    private fun toSubscriptionPayload(view: SubscriptionView) = SubscriptionPayload(
        userId = view.userId.toString(),
        tier = view.tier,
        status = view.status,
        expiryDate = view.expiryDate,
        amountPaid = view.totalPaid,
        mpesaTransactionId = view.mpesaTransactionId,
    )
}
