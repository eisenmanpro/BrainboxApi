package com.afrithecus.brainbox.api.auth.web

import jakarta.validation.constraints.NotBlank

/**
 * Phone-verification payloads (docs/phone_verification.md). The code is only ever sent over SMS,
 * never returned by the API.
 */
data class PhoneVerificationStatusPayload(
    val enabled: Boolean,
    val phoneNumber: String?,
    val verified: Boolean,
    val canRequest: Boolean,
    /** Seconds until another code may be requested; 0 means now. */
    val resendAfterSeconds: Long,
)

data class PhoneVerificationMessagePayload(
    val success: Boolean,
    val message: String,
    val resendAfterSeconds: Long? = null,
)

data class ConfirmPhoneVerificationRequest(
    @field:NotBlank
    val code: String,
)
