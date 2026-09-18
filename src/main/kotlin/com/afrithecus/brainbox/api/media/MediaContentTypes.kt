package com.afrithecus.brainbox.api.media

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The formats an upload may actually be, keyed off its leading bytes rather than
 * the client-declared multipart content type. The server stores and serves the
 * detected format's canonical type and extension, so a file can no longer be
 * smuggled in under a different declared type or an attacker-chosen extension.
 *
 * The signatures are the shortest prefix that still identifies the format
 * unambiguously (the 4-byte PNG signature, the 4-byte `%PDF`, the 3-byte JPEG
 * SOI), so a minimal fixture is accepted exactly as a real file is.
 */
enum class MediaKind(val contentType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    MP4("video/mp4", "mp4"),
    WEBM("video/webm", "webm"),
    QUICKTIME("video/quicktime", "mov"),
    PDF("application/pdf", "pdf"),
    EPUB("application/epub+zip", "epub"),
    MP3("audio/mpeg", "mp3"),
    M4A("audio/mp4", "m4a"),
    WAV("audio/wav", "wav"),
    OGG("audio/ogg", "ogg"),
    DOC("application/msword", "doc"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    TEXT("text/plain", "txt"),
    ;

    val isImage: Boolean get() = this == PNG || this == JPEG || this == GIF || this == WEBP

    val isVideo: Boolean get() = this == MP4 || this == WEBM || this == QUICKTIME

    val isAudio: Boolean get() = this == MP3 || this == M4A || this == WAV || this == OGG

    val isDocument: Boolean get() = this == PDF || this == EPUB || this == DOC || this == DOCX || this == TEXT
}

/**
 * Content-type detection by magic bytes. Returns null for anything unrecognised,
 * including a ZIP that is not an EPUB or a Word document and any binary that is not
 * on the allow-list, so the caller fails closed.
 */
object MediaContentTypes {

    /** The kind a declared content type denotes, or null when it is not supported. */
    fun forContentType(raw: String): MediaKind? {
        val declared = raw.trim().lowercase().substringBefore(';').trim()
        return when (declared) {
            "image/jpg", "image/pjpeg" -> MediaKind.JPEG
            "application/x-pdf" -> MediaKind.PDF
            "text/x-markdown", "text/markdown" -> MediaKind.TEXT
            else -> MediaKind.entries.firstOrNull { it.contentType == declared }
        }
    }

    /** The kind a client filename extension denotes, or null when it is not supported. */
    fun forExtension(raw: String): MediaKind? {
        val extension = raw.trim().lowercase().substringAfterLast('.', "")
        return MediaKind.entries.firstOrNull { it.extension == extension }
    }

    fun detect(bytes: ByteArray): MediaKind? {
        if (bytes.isEmpty()) return null
        return when {
            startsWith(bytes, PNG) -> MediaKind.PNG
            startsWith(bytes, JPEG) -> MediaKind.JPEG
            startsWith(bytes, GIF) -> MediaKind.GIF
            isRiff(bytes, "WEBP") -> MediaKind.WEBP
            startsWith(bytes, PDF) -> MediaKind.PDF
            startsWith(bytes, EBML) -> MediaKind.WEBM
            isIsoBmff(bytes) -> isoBmffKind(bytes)
            startsWith(bytes, OLE2) -> MediaKind.DOC
            startsWith(bytes, ZIP) -> zipKind(bytes)
            startsWith(bytes, ID3) -> MediaKind.MP3
            isRiff(bytes, "WAVE") -> MediaKind.WAV
            startsWith(bytes, OGG) -> MediaKind.OGG
            isMpegAudioFrame(bytes) -> MediaKind.MP3
            isPlainText(bytes) -> MediaKind.TEXT
            else -> null
        }
    }

    private fun startsWith(bytes: ByteArray, prefix: IntArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) {
            if ((bytes[index].toInt() and 0xFF) != prefix[index]) return false
        }
        return true
    }

    private fun isRiff(bytes: ByteArray, fourCc: String): Boolean =
        bytes.size >= 12 &&
            startsWith(bytes, RIFF) &&
            ascii(bytes, 8, 4) == fourCc

    private fun isIsoBmff(bytes: ByteArray): Boolean =
        bytes.size >= 12 && ascii(bytes, 4, 4) == "ftyp"

    /** Brand at offset 8 distinguishes an Apple QuickTime movie and an M4A audio clip from MP4. */
    private fun isoBmffKind(bytes: ByteArray): MediaKind {
        val brand = ascii(bytes, 8, 4)
        return when {
            brand.startsWith("M4A") || brand.startsWith("M4B") -> MediaKind.M4A
            brand == "qt  " -> MediaKind.QUICKTIME
            else -> MediaKind.MP4
        }
    }

    /** An EPUB stores `application/epub+zip` uncompressed first; a DOCX carries a `word/` part. */
    private fun zipKind(bytes: ByteArray): MediaKind? = when {
        indexOfAscii(bytes, "mimetypeapplication/epub+zip") >= 0 -> MediaKind.EPUB
        indexOfAscii(bytes, "word/") >= 0 -> MediaKind.DOCX
        else -> null
    }

    /** MPEG audio frame sync (not ADTS AAC, whose layer bits are 00). */
    private fun isMpegAudioFrame(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xE0) == 0xE0 && (second and 0x06) != 0
    }

    /**
     * Strict UTF-8 with no NUL and no control character other than tab/newline/CR.
     * A binary file with a text-looking prefix is not enough: the whole payload must
     * decode.
     */
    private fun isPlainText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: Exception) {
            return false
        }
        return text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (bytes.size < offset + length) "" else String(bytes, offset, length, Charsets.US_ASCII)

    private fun indexOfAscii(bytes: ByteArray, needle: String): Int {
        if (needle.isEmpty() || bytes.size < needle.length) return -1
        val target = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (start in 0..bytes.size - target.size) {
            for (index in target.indices) {
                if (bytes[start + index] != target[index]) continue@outer
            }
            return start
        }
        return -1
    }

    private val PNG = intArrayOf(0x89, 0x50, 0x4E, 0x47)
    private val JPEG = intArrayOf(0xFF, 0xD8, 0xFF)
    private val GIF = intArrayOf(0x47, 0x49, 0x46, 0x38)
    private val RIFF = intArrayOf(0x52, 0x49, 0x46, 0x46)
    private val PDF = intArrayOf(0x25, 0x50, 0x44, 0x46)
    private val EBML = intArrayOf(0x1A, 0x45, 0xDF, 0xA3)
    private val OLE2 = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
    private val ZIP = intArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ID3 = intArrayOf(0x49, 0x44, 0x33)
    private val OGG = intArrayOf(0x4F, 0x67, 0x67, 0x53)
}
