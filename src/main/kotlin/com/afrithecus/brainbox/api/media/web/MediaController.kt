package com.afrithecus.brainbox.api.media.web

import com.afrithecus.brainbox.api.media.MediaService
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
class MediaController(private val service: MediaService) {

    @GetMapping("/{filename}")
    fun download(@PathVariable filename: String): ResponseEntity<ByteArray> {
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
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
            .body(bytes)
    }
}
