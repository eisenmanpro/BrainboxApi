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
        Files.createDirectories(root)
        Files.write(resolve(key), bytes)
    }

    override fun get(key: String): ByteArray? {
        val path = resolve(key)
        return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }

    override fun delete(key: String) {
        runCatching { Files.deleteIfExists(resolve(key)) }
    }

    /** Opaque keys only: reject separators so a crafted key cannot escape the root. */
    private fun resolve(key: String): Path {
        require(key.isNotBlank() && !key.contains("..") && key.none { it == '/' || it == '\\' }) {
            "Invalid object key"
        }
        return root.resolve(key).normalize()
    }
}
