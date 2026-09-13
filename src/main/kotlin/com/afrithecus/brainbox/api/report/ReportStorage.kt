package com.afrithecus.brainbox.api.report

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Local-disk storage for rendered report PDFs. Files are keyed by an opaque
 * storage name (the job id) so the on-disk path never leaks into a URL; the
 * signed download endpoint resolves the job and reads its file.
 */
@Service
class ReportStorage(properties: ReportProperties) {

    private val root: Path = Paths.get(properties.storageDir).toAbsolutePath().normalize()

    fun save(storageName: String, bytes: ByteArray): Long {
        Files.createDirectories(root)
        val path = resolve(storageName)
        Files.write(path, bytes)
        return Files.size(path)
    }

    fun read(storageName: String): ByteArray? {
        val path = resolve(storageName)
        return if (Files.exists(path)) Files.readAllBytes(path) else null
    }

    fun delete(storageName: String) {
        runCatching { Files.deleteIfExists(resolve(storageName)) }
    }

    /** Repeat-safe prune for files whose job row has been retained past the window. */
    fun deleteOlderThan(seconds: Long) {
        if (!Files.exists(root)) return
        val cutoff = System.currentTimeMillis() - seconds * 1000
        Files.list(root).use { entries ->
            entries.filter { Files.isRegularFile(it) }.forEach { path ->
                runCatching {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun resolve(storageName: String): Path {
        // Opaque names only: reject separators so a crafted id cannot escape root.
        require(storageName.isNotBlank() && storageName.none { it == '/' || it == '\\' } && !storageName.contains("..")) {
            "Invalid report storage name"
        }
        return root.resolve(storageName).normalize()
    }
}
