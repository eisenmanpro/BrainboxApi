package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Local-disk media storage for uploads (CBC projects, homework attachments).
 * Returns an absolute URL built from the current request so clients can fetch it
 * directly. S3/MinIO presigned uploads replace this in Phase 6.
 */
@Service
class MediaService(
    @Value("\${app.media.local-dir:./data/media}") private val localDir: String,
) {

    private val root: Path = Paths.get(localDir).toAbsolutePath().normalize()

    fun store(file: MultipartFile): MediaUploadResponsePayload {
        if (file.isEmpty) throw invalidArgument("An upload file is required")
        val contentType = file.contentType ?: ""
        val isImage = contentType.startsWith("image/")
        val isVideo = contentType.startsWith("video/")
        if (!isImage && !isVideo) throw invalidArgument("Only image or video uploads are supported")
        Files.createDirectories(root)
        val filename = UUID.randomUUID().toString() + extensionFor(file.originalFilename, contentType)
        file.transferTo(root.resolve(filename).toFile())
        val url = ServletUriComponentsBuilder.fromCurrentContextPath().path("/media/").path(filename).toUriString()
        return MediaUploadResponsePayload(url = url, mediaType = if (isVideo) "VIDEO" else "IMAGE")
    }

    fun load(filenameRaw: String): Pair<Resource, String> {
        val filename = filenameRaw.trim()
        if (!FILENAME_REGEX.matches(filename)) throw notFound("Media not found")
        val path = root.resolve(filename).normalize()
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw notFound("Media not found")
        return FileSystemResource(path) to contentTypeFor(filename)
    }

    private fun extensionFor(original: String?, contentType: String): String {
        val fromName = original?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
        if (fromName != null) return "." + fromName
        return when (contentType) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            else -> ""
        }
    }

    private fun contentTypeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    private companion object {
        val FILENAME_REGEX = Regex("^[A-Za-z0-9-]{1,64}\\.[A-Za-z0-9]{1,5}$")
    }
}
