package com.afrithecus.brainbox.api.ml

import com.afrithecus.brainbox.api.ml.web.AdaptiveModelManifest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.security.MessageDigest

/**
 * Publishes the adaptive-difficulty ONNX model and its integrity metadata.
 *
 * The model binary is a deployment artifact, not a repository file: the operator
 * places it on disk and sets `ML_MODEL_PATH`. The SHA-256 is computed once and reused
 * until the file's size or mtime changes, so a manifest read is cheap.
 *
 * When no model is deployed every accessor returns null and the controller answers 503;
 * the app then keeps using its on-device rule-based engine rather than failing.
 */
@Service
class ModelDistributionService(
    @Value("\${app.ml.model.path:}") private val configuredPath: String = "",
    @Value("\${app.ml.model.version:1}") private val version: String = "1",
    @Value("\${app.ml.model.name:adaptive_difficulty}") private val name: String = "adaptive_difficulty",
    @Value("\${app.ml.model.file-name:adaptive_difficulty.onnx}") private val fileName: String = "adaptive_difficulty.onnx",
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Server-relative URL the client resolves against its API base. */
    private val fileUrl = "/models/adaptive-difficulty/file"

    private data class Resolved(
        val file: File,
        val sha256: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    @Volatile
    private var cache: Resolved? = null

    /** The manifest, or null when no model is deployed. */
    fun manifest(): AdaptiveModelManifest? {
        val resolved = resolve() ?: return null
        return AdaptiveModelManifest(
            name = name,
            version = version,
            fileName = fileName,
            url = fileUrl,
            sha256 = resolved.sha256,
            sizeBytes = resolved.sizeBytes,
        )
    }

    /** The model file plus its integrity metadata, or null when none is deployed. */
    fun model(): ModelFile? {
        val resolved = resolve() ?: return null
        return ModelFile(resolved.file, resolved.sha256, resolved.sizeBytes)
    }

    data class ModelFile(val file: File, val sha256: String, val sizeBytes: Long)

    private fun resolve(): Resolved? {
        val raw = configuredPath.trim()
        if (raw.isEmpty()) return null
        val file = File(raw)
        if (!file.isFile || file.length() <= 0L) {
            log.debug("Adaptive model path is configured but not a readable file: {}", raw)
            return null
        }

        val current = cache
        if (current != null &&
            current.file.absolutePath == file.absolutePath &&
            current.sizeBytes == file.length() &&
            current.lastModified == file.lastModified()
        ) {
            return current
        }

        return synchronized(this) {
            val again = cache
            if (again != null &&
                again.file.absolutePath == file.absolutePath &&
                again.sizeBytes == file.length() &&
                again.lastModified == file.lastModified()
            ) {
                again
            } else {
                Resolved(
                    file = file,
                    sha256 = sha256(file),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                ).also { cache = it }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
