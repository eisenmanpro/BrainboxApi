package com.afrithecus.brainbox.api.ml.web

import com.afrithecus.brainbox.api.ml.ModelDistributionService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public adaptive-difficulty model distribution (the app fetches it on startup,
 * before any session exists, so both routes are `permitAll`).
 *
 * The manifest carries the SHA-256 and size of the exact bytes served at the file
 * route, so a client verifies integrity before it loads an executable model. When no
 * model is deployed both routes answer 503 and the client falls back to its
 * rule-based engine.
 */
@RestController
@RequestMapping("/models")
class ModelDistributionController(private val service: ModelDistributionService) {

    @GetMapping("/adaptive-difficulty")
    fun manifest(): ResponseEntity<AdaptiveModelManifest> =
        service.manifest()
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()

    @GetMapping("/adaptive-difficulty/file")
    fun file(): ResponseEntity<Resource> {
        val model = service.model()
            ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, "\"${model.sha256}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(model.sizeBytes)
            .body(FileSystemResource(model.file))
    }
}
