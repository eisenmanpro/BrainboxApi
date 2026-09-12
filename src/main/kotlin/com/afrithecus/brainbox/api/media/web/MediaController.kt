package com.afrithecus.brainbox.api.media.web

import com.afrithecus.brainbox.api.media.MediaService
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** Serves locally stored uploaded media (public, immutable filenames). */
@RestController
@RequestMapping("/media")
class MediaController(private val service: MediaService) {

    @GetMapping("/{filename}")
    fun download(@PathVariable filename: String): ResponseEntity<Resource> {
        val (resource, contentType) = service.load(filename)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
            .body(resource)
    }
}
