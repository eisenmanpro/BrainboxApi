package com.afrithecus.brainbox.api.auth.web

import jakarta.validation.constraints.NotBlank

/**
 * Password-recovery payloads (docs/ongoing/api_auth_changes.md). The
 * forgot-password response is intentionally minimal so known and unknown
 * identifiers are indistinguishable (anti-enumeration).
 */
data class ForgotPasswordRequest(
    @field:NotBlank val identifier: String,
)

data class VerifyOtpRequest(
    @field:NotBlank val identifier: String,
    @field:NotBlank val otp: String,
)

data class VerifyOtpPayload(
    val success: Boolean,
    val message: String,
    val resetToken: String? = null,
)

data class ResetPasswordRequest(
    @field:NotBlank val resetToken: String,
    @field:NotBlank val newPassword: String,
)

/** Neutral {success, message} body for forgot/reset. */
data class AuthMessagePayload(
    val success: Boolean,
    val message: String,
)
