package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.web.PublicUrlBuilder
import com.afrithecus.brainbox.api.media.web.MediaController
import com.afrithecus.brainbox.api.storage.LocalObjectStorage
import com.afrithecus.brainbox.api.storage.ObjectStorage
import com.afrithecus.brainbox.api.storage.StorageProperties
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

    /** No scanner configured: the default deployment, which records uploads as unscanned. */
    private fun noScan(): MediaScanGate = MediaSecurityTestDoubles.gate()

    private fun mediaService(
        urlBuilder: PublicUrlBuilder = PublicUrlBuilder(""),
        scanGate: MediaScanGate = noScan(),
        storage: ObjectStorage = LocalObjectStorage(directory),
        maxBytes: Long = 1024L * 1024L,
    ): MediaService = MediaService(storage, StorageProperties(), urlBuilder, scanGate, maxBytes)

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("brainbox-media-test")
        service = mediaService()
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
    fun presignedBackendRedirectsDownloads() {
        val fake = object : ObjectStorage {
            override fun put(key: String, bytes: ByteArray, contentType: String) = Unit
            override fun get(key: String): ByteArray? = null
            override fun delete(key: String) = Unit
            override fun presignGet(key: String, ttl: java.time.Duration): String? =
                "https://minio.internal/brainbox/media/" + key + "?X-Amz-Signature=x"
        }
        val controller = MediaController(mediaService(storage = fake), MediaProperties())
        val response = controller.download("abc.png")
        assertEquals(302, response.statusCode.value())
        assertTrue(response.headers.location.toString().startsWith("https://minio.internal/brainbox/media/abc.png"))
    }

    @Test
    fun servedDownloadCarriesTheAntiSniffingHeaders() {
        val stored = service.store(MockMultipartFile("file", "note.png", "image/png", png))
        val filename = stored.url.substringAfterLast("/media/")
        val response = MediaController(service, MediaProperties()).download(filename)
        assertEquals("nosniff", response.headers.getFirst("X-Content-Type-Options"))
        assertEquals("inline", response.headers.getFirst("Content-Disposition"))
        assertEquals("image/png", response.headers.contentType.toString())
    }

    @Test
    fun mediaCacheStaysPrivateUntilACdnIsConfigured() {
        val stored = service.store(MockMultipartFile("file", "note.png", "image/png", png))
        val filename = stored.url.substringAfterLast("/media/")

        // Default: immutable bytes, but no shared cache may store the response.
        val privateResponse = MediaController(service, MediaProperties()).download(filename)
        val privateCache = privateResponse.headers.cacheControl!!
        assertTrue(privateCache.startsWith("max-age=") || privateCache.contains("max-age="), privateCache)
        assertTrue(privateCache.contains("private"), privateCache)
        assertTrue(!privateCache.contains("public"), privateCache)

        // With a CDN in front the deployment flips one property and the origin says so.
        val publicProperties = MediaProperties(
            cache = MediaProperties.Cache(publicCache = true, maxAgeSeconds = 600),
        )
        val publicResponse = MediaController(service, publicProperties).download(filename)
        val publicCache = publicResponse.headers.cacheControl!!
        assertTrue(publicCache.contains("public"), publicCache)
        assertTrue(publicCache.contains("max-age=600"), publicCache)
        assertTrue(publicCache.contains("immutable"), publicCache)
    }

    @Test
    fun servedMediaUrlsUseTheConfiguredPublicBase() {
        // The CDN knob: every returned media URL points at the public base instead of the
        // API host, and the app needs no change because it treats an absolute URL as final.
        val cdn = mediaService(urlBuilder = PublicUrlBuilder("https://cdn.brainbox.co.ke/"))
        val stored = cdn.store(MockMultipartFile("file", "note.png", "image/png", png))
        assertTrue(stored.url.startsWith("https://cdn.brainbox.co.ke/media/"), stored.url)
    }

    @Test
    fun theProxiedPathRefusesAnInfectedUpload() {
        val infected = object : MediaScanner {
            override val name = "stub"
            override fun scan(bytes: ByteArray, contentType: String?): MediaScanResult =
                MediaScanResult(MediaScanStatus.INFECTED, "Eicar-Test-Signature")
        }
        val scanning = mediaService(
            scanGate = MediaSecurityTestDoubles.gate(
                scanner = infected,
                settings = MediaSecurityTestDoubles.settings(scanEnabled = true, provider = "stub"),
            ),
        )

        val failure = assertFailsWith<ApiException> {
            scanning.store(MockMultipartFile("file", "note.png", "image/png", png))
        }
        assertTrue(failure.message!!.contains("malware scan"), failure.message!!)
        assertTrue(failure.message!!.contains("Eicar-Test-Signature"), failure.message!!)
    }

    @Test
    fun aBrokenScannerRefusesTheUploadUnlessTheDeploymentOptsOut() {
        val broken = object : MediaScanner {
            override val name = "stub"
            override fun scan(bytes: ByteArray, contentType: String?): MediaScanResult =
                MediaScanResult(MediaScanStatus.ERROR, "The malware scanner is unavailable")
        }
        val failClosed = mediaService(
            scanGate = MediaSecurityTestDoubles.gate(
                scanner = broken,
                settings = MediaSecurityTestDoubles.settings(scanEnabled = true, provider = "stub"),
            ),
        )
        assertFailsWith<ApiException> {
            failClosed.store(MockMultipartFile("file", "note.png", "image/png", png))
        }

        val failOpen = mediaService(
            scanGate = MediaSecurityTestDoubles.gate(
                scanner = broken,
                settings = MediaSecurityTestDoubles.settings(
                    scanEnabled = true,
                    provider = "stub",
                    failOpen = true,
                ),
            ),
        )
        assertTrue(
            failOpen.store(MockMultipartFile("file", "note.png", "image/png", png)).url.isNotBlank(),
        )
    }
}
