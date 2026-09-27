package com.afrithecus.brainbox.api.ml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The manifest must describe the exact bytes the file route serves, so the app can
 * verify integrity before it loads an executable model.
 */
class ModelDistributionServiceTest {

    /** SHA-256 of the ASCII string "hello" — an independent, well-known value. */
    private val helloSha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun service(path: String) = ModelDistributionService(
        configuredPath = path,
        version = "7",
        name = "adaptive_difficulty",
        fileName = "adaptive_difficulty.onnx",
    )

    @Test
    fun `no configured path publishes nothing`() {
        val service = service("")
        assertNull(service.manifest())
        assertNull(service.model())
    }

    @Test
    fun `a missing file publishes nothing`(@TempDir dir: File) {
        val service = service(File(dir, "absent.onnx").absolutePath)
        assertNull(service.manifest())
        assertNull(service.model())
    }

    @Test
    fun `an empty file publishes nothing`(@TempDir dir: File) {
        val file = File(dir, "empty.onnx").apply { writeBytes(ByteArray(0)) }
        val service = service(file.absolutePath)
        assertNull(service.manifest())
        assertNull(service.model())
    }

    @Test
    fun `the manifest describes the served bytes`(@TempDir dir: File) {
        val file = File(dir, "adaptive_difficulty.onnx").apply { writeText("hello") }
        val service = service(file.absolutePath)

        val manifest = service.manifest()!!
        assertEquals("hello", file.readText())
        assertEquals(helloSha256, manifest.sha256)
        assertEquals(5L, manifest.sizeBytes)
        assertEquals("/models/adaptive-difficulty/file", manifest.url)
        assertEquals("7", manifest.version)
        assertEquals("adaptive_difficulty.onnx", manifest.fileName)
        assertEquals("application/octet-stream", manifest.contentType)

        val model = service.model()!!
        assertEquals(5L, model.sizeBytes)
        assertEquals(helloSha256, model.sha256)
        assertEquals(file.absolutePath, model.file.absolutePath)
    }

    @Test
    fun `the cached digest is refreshed when the file changes`(@TempDir dir: File) {
        val file = File(dir, "adaptive_difficulty.onnx").apply { writeText("hello") }
        val service = service(file.absolutePath)
        assertEquals(helloSha256, service.manifest()!!.sha256)

        // A redeployed model must be re-hashed, not served with the stale digest.
        file.writeText("goodbye")
        file.setLastModified(file.lastModified() + 2_000L)
        val updated = service.manifest()!!
        assertNotEquals(helloSha256, updated.sha256)
        assertEquals(file.length(), updated.sizeBytes)
    }
}
