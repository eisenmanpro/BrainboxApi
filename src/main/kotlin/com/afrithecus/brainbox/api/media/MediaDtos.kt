package com.afrithecus.brainbox.api.media

/** Hosted media returned by upload endpoints (CBC projects, homework attachments). */
data class MediaUploadResponsePayload(
    val url: String,
    val mediaType: String = "IMAGE",
)
