package com.afrithecus.brainbox.api.exams.web

import com.afrithecus.brainbox.api.common.config.HttpCachingConfig
import com.afrithecus.brainbox.api.exams.PastPaperService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Past-paper discovery + attempt recording (doc 02 §5). */
@RestController
@RequestMapping("/past-papers")
class PastPapersController(private val service: PastPaperService) {

    @GetMapping("/all")
    fun all(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) grade: String?,
    ): List<DocumentItem> = service.list(currentUser.userId, subject, grade)

    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam q: String,
    ): List<DocumentItem> = service.search(currentUser.userId, q)

    /** Origin-cached (H1): ETag + `max-age=60, must-revalidate, private`. */
    @GetMapping("/{examId}/content")
    fun content(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
    ): ResponseEntity<ExamContentPayload> =
        ResponseEntity.ok()
            .cacheControl(HttpCachingConfig.CONTENT_READ_CACHE_CONTROL)
            .body(service.content(currentUser.userId, examId))

    @PostMapping("/{examId}/attempts")
    fun recordAttempt(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable examId: String,
        @Valid @RequestBody request: PastPaperAttemptRequest,
    ): ResponseEntity<Void> {
        service.recordAttempt(currentUser.userId, examId, request)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
