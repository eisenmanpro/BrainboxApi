package com.afrithecus.brainbox.api.media

/** The upload surfaces, each with its own size cap and allow-list. */
enum class MediaPurpose { MEDIA, HOMEWORK_ATTACHMENT, DOCUMENT }

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

    /** Images and videos (the default media upload). */
    fun allowsMedia(kind: MediaKind): Boolean = kind.isImage || kind.isVideo

    fun allows(purpose: MediaPurpose, kind: MediaKind): Boolean = when (purpose) {
        MediaPurpose.MEDIA -> allowsMedia(kind)
        MediaPurpose.HOMEWORK_ATTACHMENT -> kind in HOMEWORK_ATTACHMENT_KINDS
        MediaPurpose.DOCUMENT -> kind in DOCUMENT_KINDS
    }
}
