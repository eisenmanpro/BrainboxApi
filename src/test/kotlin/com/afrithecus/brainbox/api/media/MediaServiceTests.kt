package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.media.web.MediaController
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The upload policy: store only the detected type under a server-chosen extension,
 * cap the size, and apply each surface's allow-list. The download response is
 * hardened so a stored document can never be sniffed into active content.
 */
class MediaServiceTests {

    private lateinit var directory: Path
    private lateinit var service: MediaService

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("brainbox-media-test")
        service = MediaService(directory.toString(), 1024L * 1024L)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun storesTheDetectedTypeUnderAServerChosenExtension() {
        // The client filename claims .html; the stored name must not.
        val stored = service.store(MockMultipartFile("file", "holiday.html", "image/png", png))
        assertTrue(stored.url.endsWith(".png"), stored.url)
        assertEquals("IMAGE", stored.mediaType)
    }

    @Test
    fun rejectsAnUploadWhoseBytesDoNotMatchAnAllowedType() {
        assertFailsWith<ApiException> {
            service.store(MockMultipartFile("file", "x.png", "image/png", "<html>hi</html>".toByteArray()))
        }
    }

    @Test
    fun rejectsAnUploadOverTheConfiguredCap() {
        val oversized = ByteArray((1024 * 1024) + 1)
        oversized[0] = 0x89.toByte()
        oversized[1] = 0x50
        oversized[2] = 0x4E
        oversized[3] = 0x47
        assertFailsWith<ApiException> {
            service.store(MockMultipartFile("file", "big.png", "image/png", oversized))
        }
    }

    @Test
    fun homeworkAttachmentsAllowTheirSetAndRejectVideo() {
        assertTrue(
            service.storeHomeworkAttachment(MockMultipartFile("file", "ch.pdf", "application/pdf", "%PDF".toByteArray()))
                .url.isNotBlank(),
        )
        assertTrue(
            service.storeHomeworkAttachment(MockMultipartFile("file", "note.txt", "text/plain", "working".toByteArray()))
                .url.isNotBlank(),
        )
        assertFailsWith<ApiException> {
            service.storeHomeworkAttachment(
                MockMultipartFile("file", "clip.mp4", "video/mp4", byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()),
            )
        }
    }

    @Test
    fun documentsAllowPdfAndRejectWord() {
        assertTrue(
            service.storeDocument(MockMultipartFile("file", "notes.pdf", "application/pdf", "%PDF-1.7".toByteArray()))
                .url.isNotBlank(),
        )
        assertFailsWith<ApiException> {
            service.storeDocument(
                MockMultipartFile(
                    "file",
                    "notes.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "word/document.xml".toByteArray(),
                ),
            )
        }
    }

    @Test
    fun servedDownloadCarriesTheAntiSniffingHeaders() {
        val stored = service.store(MockMultipartFile("file", "note.png", "image/png", png))
        val filename = stored.url.substringAfterLast("/media/")
        val response = MediaController(service).download(filename)
        assertEquals("nosniff", response.headers.getFirst("X-Content-Type-Options"))
        assertEquals("inline", response.headers.getFirst("Content-Disposition"))
        assertEquals("image/png", response.headers.contentType.toString())
    }
}
