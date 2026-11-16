package com.afrithecus.brainbox.api.media

/** The upload surfaces, each with its own size cap and allow-list. */
enum class MediaPurpose { MEDIA, HOMEWORK_ATTACHMENT, DOCUMENT, CHAT_ATTACHMENT, GALLERY }

/**
 * Size caps and allow-lists shared by the proxied multipart upload and the
 * presigned direct upload, so both paths accept exactly the same files.
 */
object MediaPolicies {

    const val BYTES_PER_MB = 1024L * 1024L
    const val ATTACHMENT_MAX_BYTES = 10L * BYTES_PER_MB

    /**
     * Report branding (school logo / stamp, D16/A23): the same 2 MB cap the PDF
     * renderer enforces when it decodes an image, so a stored image is always
     * renderable instead of being silently dropped at report time.
     */
    const val BRANDING_MAX_BYTES = 2L * BYTES_PER_MB

    /**
     * Formats ImageIO can decode for the report branding slots. WebP is deliberately
     * excluded: it would store, but the PDF renderer cannot read it and would fall
     * back to the BrainBox mark / Exam Office stamp without telling the admin.
     */
    val BRANDING_KINDS = setOf(MediaKind.PNG, MediaKind.JPEG, MediaKind.GIF)

    /**
     * A school's own landing gallery: 5 MB per image, and WebP is welcome because the
     * landing is a browser surface (unlike the report branding slots the PDF renderer
     * decodes).
     */
    const val GALLERY_MAX_BYTES = 5L * BYTES_PER_MB
    val GALLERY_KINDS = setOf(MediaKind.PNG, MediaKind.JPEG, MediaKind.GIF, MediaKind.WEBP)

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
        MediaPurpose.GALLERY -> kind in GALLERY_KINDS
    }
}
