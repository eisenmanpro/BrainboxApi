package com.afrithecus.brainbox.api.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Magic-byte detection decides the stored and served type, so it must recognise the
 * real formats without trusting anything the client declares. HTML and raw binary
 * are the two cases that matter for the smuggling hole this closes.
 */
class MediaContentTypesTests {

    @Test
    fun detectsTheSupportedFormatsFromTheirLeadingBytes() {
        assertEquals(MediaKind.PNG, MediaContentTypes.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(MediaKind.JPEG, MediaContentTypes.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(MediaKind.GIF, MediaContentTypes.detect("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertEquals(MediaKind.WEBP, MediaContentTypes.detect(riff("WEBP")))
        assertEquals(MediaKind.WAV, MediaContentTypes.detect(riff("WAVE")))
        assertEquals(MediaKind.PDF, MediaContentTypes.detect("%PDF-1.7".toByteArray(Charsets.US_ASCII)))
        assertEquals(MediaKind.MP4, MediaContentTypes.detect(isoBmff("isom")))
        assertEquals(MediaKind.QUICKTIME, MediaContentTypes.detect(isoBmff("qt  ")))
        assertEquals(MediaKind.M4A, MediaContentTypes.detect(isoBmff("M4A ")))
        assertEquals(MediaKind.WEBM, MediaContentTypes.detect(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(8)))
        assertEquals(MediaKind.DOC, MediaContentTypes.detect(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())))
        assertEquals(MediaKind.OGG, MediaContentTypes.detect("OggS".toByteArray(Charsets.US_ASCII) + ByteArray(8)))
        assertEquals(MediaKind.MP3, MediaContentTypes.detect("ID3".toByteArray(Charsets.US_ASCII) + ByteArray(8)))
        assertEquals(MediaKind.TEXT, MediaContentTypes.detect("hello world".toByteArray()))
    }

    @Test
    fun distinguishesZipDocumentsFromArbitraryZips() {
        val docx = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "[Content_Types].xmlword/document.xml".toByteArray()
        assertEquals(MediaKind.DOCX, MediaContentTypes.detect(docx))
        val epub = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "mimetypeapplication/epub+zip".toByteArray()
        assertEquals(MediaKind.EPUB, MediaContentTypes.detect(epub))
        // A plain archive is not an allowed document.
        assertNull(MediaContentTypes.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "notes.txt".toByteArray()))
    }

    @Test
    fun rejectsBinaryAndNeverConfusesTextWithAMediaType() {
        assertNull(MediaContentTypes.detect(byteArrayOf(1, 2, 3)))
        assertNull(MediaContentTypes.detect(ByteArray(16)))
        // HTML and SVG are valid text; the caller's allow-list, not the sniffer,
        // decides whether text is acceptable for that surface.
        assertEquals(MediaKind.TEXT, MediaContentTypes.detect("<html><script>alert(1)</script></html>".toByteArray()))
        assertEquals(MediaKind.TEXT, MediaContentTypes.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".toByteArray()))
    }

    private fun riff(fourCc: String): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + fourCc.toByteArray(Charsets.US_ASCII)

    private fun isoBmff(brand: String): ByteArray =
        byteArrayOf(0, 0, 0, 0x20) + "ftyp".toByteArray(Charsets.US_ASCII) + brand.toByteArray(Charsets.US_ASCII)
}
