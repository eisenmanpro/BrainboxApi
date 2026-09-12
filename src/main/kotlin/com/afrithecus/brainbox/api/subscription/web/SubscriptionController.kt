package com.afrithecus.brainbox.api.subscription.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The caller's authoritative subscription (doc 14 §3, doc 01 §4). */
@RestController
@RequestMapping("/subscriptions")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal currentUser: CurrentUser): SubscriptionPayload {
        val view = subscriptionService.view(currentUser.userId)
        return SubscriptionPayload(
            userId = view.userId.toString(),
            tier = view.tier,
            status = view.status,
            expiryDate = view.expiryDate,
            amountPaid = view.totalPaid,
        )
    }
}
