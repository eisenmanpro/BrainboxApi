package com.afrithecus.brainbox.api.auth.web

import jakarta.validation.constraints.NotBlank

/**
 * Email verification and email recovery payloads (gaps.md items 3/4). The identifier is always the
 * account's email address; the code is a 6-digit one-time code and is never echoed back.
 */
data class EmailCodeRequest(
    @field:NotBlank val identifier: String,
)

data class EmailCodeVerifyRequest(
    @field:NotBlank val identifier: String,
    @field:NotBlank val code: String,
)

/** Neutral {success, message} body, plus the resend cool-down so the client can start its timer. */
data class EmailCodeMessagePayload(
    val success: Boolean,
    val message: String,
    val resendAfterSeconds: Long = 0,
)

/** "Which email did I sign up with?" answer. Only the masked address is ever disclosed. */
data class RecoveredEmailPayload(
    val found: Boolean,
    val maskedEmail: String? = null,
)
