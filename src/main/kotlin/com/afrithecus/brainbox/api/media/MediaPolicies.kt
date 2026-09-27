package com.afrithecus.brainbox.api.media

/** The upload surfaces, each with its own size cap and allow-list. */
enum class MediaPurpose { MEDIA, HOMEWORK_ATTACHMENT, DOCUMENT, CHAT_ATTACHMENT }

/**
 * Size caps and allow-lists shared by the proxied multipart upload and the
 * presigned direct upload, so both paths accept exactly the same files.
 */
object MediaPolicies {

    const val BYTES_PER_MB = 1024L * 1024L
    const val ATTACHMENT_MAX_BYTES = 10L * BYTES_PER_MB

    val HOMEWORK_ATTACHMENT_KINDS = setOf(
        MediaKind.PDF,
        MediaKind.DOC,
        MediaKind.DOCX,
        MediaKind.TEXT,
        MediaKind.JPEG,
        MediaKind.PNG,
    )
    val DOCUMENT_KINDS = setOf(MediaKind.PDF, MediaKind.EPUB, MediaKind.TEXT)

    /**
     * What a chat message may carry: the same documents and images a homework
     * attachment may, so a class-chat file can be uploaded client-direct with the same
     * allow-list the multipart attachment route enforces.
     */
    val CHAT_ATTACHMENT_KINDS = HOMEWORK_ATTACHMENT_KINDS

    /** Images and videos (the default media upload). */
    fun allowsMedia(kind: MediaKind): Boolean = kind.isImage || kind.isVideo

    fun allows(purpose: MediaPurpose, kind: MediaKind): Boolean = when (purpose) {
        MediaPurpose.MEDIA -> allowsMedia(kind)
        MediaPurpose.HOMEWORK_ATTACHMENT -> kind in HOMEWORK_ATTACHMENT_KINDS
        MediaPurpose.CHAT_ATTACHMENT -> kind in CHAT_ATTACHMENT_KINDS
        MediaPurpose.DOCUMENT -> kind in DOCUMENT_KINDS
    }
}
