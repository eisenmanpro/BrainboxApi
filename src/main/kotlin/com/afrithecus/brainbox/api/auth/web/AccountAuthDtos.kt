package com.afrithecus.brainbox.api.auth.web

import jakarta.validation.constraints.NotBlank

/** Account security payloads (Android ProfileModels / UserModels). */
data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank val newPassword: String,
)

/**
 * A request to register a new school. Does NOT create the school immediately;
 * it is queued for admin review. submittedBy is accepted for wire compatibility
 * but ignored — the actor comes from the token.
 */
data class SchoolRegistrationRequest(
    val requestId: String = "",
    @field:NotBlank val schoolName: String,
    val address: String? = null,
    val submittedBy: String? = null,
    val submittedAt: Long? = null,
)
