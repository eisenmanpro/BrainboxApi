package com.afrithecus.brainbox.api.storage

import java.nio.file.Files
import java.nio.file.Path

/**
 * Filesystem-backed store: the current single-node behaviour, and the default
 * when no S3 endpoint is configured. On a deployment that shares one volume across
 * API instances it also behaves as shared storage.
 */
class LocalObjectStorage(rootDir: Path) : ObjectStorage {

    private val root: Path = rootDir.toAbsolutePath().normalize()

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        val path = resolve(key)
        // A namespaced key (quarantine/<key>) lives in a subdirectory; create it first.
        path.parent?.let { Files.createDirectories(it) }
        Files.createDirectories(root)
        Files.write(path, bytes)
    }

    override fun get(key: String): ByteArray? {
        val path = resolve(key)
        return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }

    override fun delete(key: String) {
        runCatching { Files.deleteIfExists(resolve(key)) }
    }

    /**
     * Size and detected content type without reading the object. Implemented so the local
     * backend behaves like the others for the code paths that inspect an object (the presign
     * confirm step HEADs before it reads).
     */
    override fun head(key: String): ObjectMetadata? {
        val path = resolve(key)
        if (!Files.isRegularFile(path)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        val contentType = runCatching { Files.probeContentType(path) }.getOrNull()
        return ObjectMetadata(sizeBytes = size, contentType = contentType)
    }

    /** A bounded prefix read, used for format detection. */
    override fun getRange(key: String, offset: Long, length: Int): ByteArray? {
        val path = resolve(key)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            java.io.RandomAccessFile(path.toFile(), "r").use { file ->
                if (offset >= file.length()) return@use ByteArray(0)
                file.seek(offset)
                val size = minOf(length.toLong(), file.length() - offset).toInt()
                val buffer = ByteArray(size)
                file.readFully(buffer)
                buffer
            }
        }.getOrNull()
    }

    /**
     * A server-generated key, optionally namespaced (`quarantine/<key>`). Traversal is still
     * refused: no parent segments, no absolute paths, no backslashes, and the resolved path
     * must stay under the root.
     */
    private fun resolve(key: String): Path {
        require(
            key.isNotBlank() &&
                !key.contains("..") &&
                !key.startsWith("/") &&
                !key.contains('\\')
        ) { "Invalid object key" }
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Invalid object key" }
        return path
    }
}
