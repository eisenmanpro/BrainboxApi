package com.afrithecus.brainbox.api.payments.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.payments.PaymentService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * M-Pesa payments relayed through IntaSend (doc 14 section 6, doc 07 section 3).
 * The client posts form-encoded fields, mirroring its PaymentApi. The callback is
 * public because IntaSend cannot present a bearer token; it is authenticated by
 * the shared challenge instead.
 */
@RestController
@RequestMapping("/payments")
class PaymentController(
    private val payments: PaymentService,
) {

    @PostMapping("/stk-push")
    fun stkPush(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam userId: String,
        @RequestParam amount: Int,
        @RequestParam phoneNumber: String,
        @RequestParam tier: String,
        @RequestParam(required = false) clientRequestId: String? = null,
    ): StkPushResponse = payments.stkPush(currentUser, userId, amount, phoneNumber, tier, clientRequestId)

    @PostMapping("/query")
    fun query(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam transactionId: String,
    ): PaymentQueryResponse = payments.query(currentUser, transactionId)

    @PostMapping("/mpesa/callback")
    fun callback(@RequestBody payload: JsonNode): PaymentCallbackAck = payments.handleCallback(payload)
}
