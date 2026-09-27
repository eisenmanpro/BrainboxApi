package com.afrithecus.brainbox.api.ml

import com.afrithecus.brainbox.api.ml.web.AdaptiveModelManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files

/**
 * End-to-end contract for a deployed model: the manifest a client reads must describe
 * the exact bytes the file route serves, because the client verifies both before it
 * loads executable weights.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModelDistributionServingTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `the manifest describes the served model bytes`() {
        val json = mockMvc.perform(get("/models/adaptive-difficulty"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val manifest = objectMapper.readValue(json, AdaptiveModelManifest::class.java)

        assertEquals(MODEL_NAME, manifest.name)
        assertEquals("7", manifest.version)
        assertEquals("hello", MODEL_FILE.readText())
        assertEquals(MODEL_FILE.length(), manifest.sizeBytes)
        assertEquals(SHA256_OF_HELLO, manifest.sha256)
        assertEquals("/models/adaptive-difficulty/file", manifest.url)
        assertEquals("application/octet-stream", manifest.contentType)

        val body = mockMvc.perform(get(manifest.url))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
            .andExpect(header().string(HttpHeaders.ETAG, "\"$SHA256_OF_HELLO\""))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, MODEL_FILE.length()))
            .andExpect(content().bytes(MODEL_FILE.readBytes()))
            .andReturn().response.contentAsByteArray

        assertEquals(manifest.sizeBytes, body.size.toLong(), "manifest size must match the served bytes")
        assertEquals(manifest.sha256, sha256(body), "manifest digest must match the served bytes")
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MODEL_NAME = "adaptive_difficulty"

        /** SHA-256 of the ASCII string "hello" — an independent, well-known value. */
        private const val SHA256_OF_HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        private val MODEL_FILE: File = Files.createTempDirectory("brainbox-model-test")
            .resolve("$MODEL_NAME.onnx")
            .toFile()
            .apply { writeText("hello") }

        @JvmStatic
        @DynamicPropertySource
        fun modelProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.ml.model.path") { MODEL_FILE.absolutePath }
            registry.add("app.ml.model.version") { "7" }
        }
    }
}
