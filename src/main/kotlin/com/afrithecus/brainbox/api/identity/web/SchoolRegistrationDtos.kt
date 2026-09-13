package com.afrithecus.brainbox.api.identity.web

/**
 * School registration moderation payloads (admin review). The creating request
 * is accepted under /auth (see auth/web/AccountAuthDtos.kt).
 */
data class SchoolRegistrationReviewRequest(
    val note: String? = null,
)

data class SchoolRegistrationRequestView(
    val id: String,
    val requestId: String,
    val schoolName: String,
    val address: String? = null,
    val submittedBy: String? = null,
    val submittedAt: Long,
    val status: String,
    val reviewedBy: String? = null,
    val reviewedAt: Long? = null,
    val reviewNote: String? = null,
    val createdAt: Long,
)
