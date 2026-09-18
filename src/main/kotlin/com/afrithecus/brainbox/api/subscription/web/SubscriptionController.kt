package com.afrithecus.brainbox.api.subscription.web

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Subscriptions (doc 14 §3, doc 01 §4, doc 07 §2). */
@RestController
@RequestMapping("/subscriptions")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal currentUser: CurrentUser): SubscriptionPayload =
        toPayload(subscriptionService.view(currentUser.userId))

    /** Self, a linked parent, or staff (doc 07 section 2.2). */
    @GetMapping("/{userId}")
    fun byUser(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
    ): SubscriptionPayload = toPayload(subscriptionService.viewFor(currentUser, parse(userId)))

    @GetMapping("/{userId}/history")
    fun history(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
    ): List<SubscriptionHistoryPayload> = subscriptionService.historyFor(currentUser, parse(userId))

    private fun toPayload(view: com.afrithecus.brainbox.api.subscription.SubscriptionView) = SubscriptionPayload(
        userId = view.userId.toString(),
        tier = view.tier,
        status = view.status,
        expiryDate = view.expiryDate,
        amountPaid = view.totalPaid,
        mpesaTransactionId = view.mpesaTransactionId,
    )

    private fun parse(raw: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument("userId is not a valid identifier")
}
