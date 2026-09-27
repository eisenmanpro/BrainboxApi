package com.afrithecus.brainbox.api.media.web

import com.afrithecus.brainbox.api.media.MediaProperties
import com.afrithecus.brainbox.api.media.MediaService
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration

/**
 * Serves stored uploads. Filenames are unguessable UUIDs and the stored extension is
 * server-derived, but the path is still public (it is fetched without a bearer
 * header, like signed report downloads), so every response is hardened: no content
 * sniffing, no script/embedding context, no referrer leak, and a forced download for
 * anything that is not an image/video/audio.
 */
@RestController
@RequestMapping("/media")
class MediaController(
    private val service: MediaService,
    private val properties: MediaProperties,
) {

    /**
     * `public, max-age=<configured>, immutable` when a CDN is fronting the deployment,
     * otherwise `private, max-age=<configured>` (the historical behaviour).
     */
    private fun cacheControl(): CacheControl {
        val maxAge = Duration.ofSeconds(properties.cache.maxAgeSeconds)
        return if (properties.cache.publicCache) {
            CacheControl.maxAge(maxAge).cachePublic().immutable()
        } else {
            CacheControl.maxAge(maxAge).cachePrivate()
        }
    }

    @GetMapping("/{filename}")
    fun download(@PathVariable filename: String): ResponseEntity<ByteArray> {
        // With an object store the stable URL redirects to a short-lived presigned
        // GET, so the bytes come straight from storage instead of the API node.
        service.presignedDownloadUrl(filename)?.let { presigned ->
            return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI(presigned))
                .cacheControl(CacheControl.noStore())
                .build()
        }
        val (bytes, contentType) = service.load(filename)
        val mediaType = MediaType.parseMediaType(contentType)
        val isRenderable = contentType.startsWith("image/") ||
            contentType.startsWith("video/") ||
            contentType.startsWith("audio/")
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; sandbox")
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Disposition", if (isRenderable) "inline" else "attachment")
            // Keys are UUIDs that are never reused, so the bytes are immutable. Until a
            // CDN or reverse proxy is in front (`app.media.cache.public=true`) the
            // response stays `private`, which keeps the no-shared-cache posture.
            .cacheControl(cacheControl())
            .body(bytes)
    }
}
