package com.afrithecus.brainbox.api.auth.web

import com.afrithecus.brainbox.api.auth.PhoneVerificationService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Authenticated phone-verification endpoints (docs/phone_verification.md). The account verifies
 * the phone number already on its profile; phone editing is not offered, so there is no
 * change-number flow that would need to clear verification.
 */
@RestController
@RequestMapping("/auth/phone/verification")
class PhoneVerificationController(
    private val service: PhoneVerificationService,
) {

    @GetMapping
    fun status(@AuthenticationPrincipal currentUser: CurrentUser): PhoneVerificationStatusPayload =
        service.status(currentUser)

    @PostMapping("/request")
    fun request(@AuthenticationPrincipal currentUser: CurrentUser): PhoneVerificationMessagePayload =
        service.request(currentUser)

    @PostMapping("/confirm")
    fun confirm(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ConfirmPhoneVerificationRequest,
    ): PhoneVerificationMessagePayload = service.confirm(currentUser, request.code)
}
