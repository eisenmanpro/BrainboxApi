package com.afrithecus.brainbox.api.media.web

import jakarta.validation.constraints.NotBlank

// ---------------------------------------------------------------------------
// Presigned client-direct upload. The client asks for a ticket, PUTs the bytes
// straight to the store, then confirms so the server can verify the real format.
// ---------------------------------------------------------------------------

data class MediaUploadInitiateRequest(
    @field:NotBlank
    val contentType: String,
    /** MEDIA (images/videos), HOMEWORK_ATTACHMENT or DOCUMENT. */
    val purpose: String = "MEDIA",
    /** Optional; used to infer the type when contentType is generic. */
    val fileName: String? = null,
)

/** Everything the client needs for the direct PUT and the confirm round trip. */
data class MediaUploadTicket(
    val uploadId: String,
    val uploadUrl: String,
    val method: String = "PUT",
    val headers: Map<String, String>,
    val key: String,
    val expiresAt: Long,
    val confirmUrl: String,
)
