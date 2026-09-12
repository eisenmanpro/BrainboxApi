package com.afrithecus.brainbox.api.notification.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.notification.NewsService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * News feed and engagement (docs/ongoing/api_news_changes.md). Reads are public;
 * comments/votes/reports require a session; writes require an admin.
 */
@RestController
@RequestMapping("/news")
class NewsController(private val service: NewsService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser?,
        @RequestParam(required = false) category: String?,
    ): List<NewsItemPayload> = service.list(current, category)

    @GetMapping("/{newsId}")
    fun detail(
        @AuthenticationPrincipal current: CurrentUser?,
        @PathVariable newsId: String,
    ): NewsItemPayload = service.detail(current, newsId)

    @GetMapping("/{newsId}/comments")
    fun comments(@PathVariable newsId: String): List<NewsCommentPayload> = service.comments(newsId)

    @PostMapping("/{newsId}/comments")
    fun addComment(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
        @Valid @RequestBody request: AddNewsCommentRequest,
    ): ResponseEntity<NewsCommentPayload> = ResponseEntity.status(HttpStatus.CREATED).body(service.addComment(current, newsId, request))

    @PostMapping("/{newsId}/vote")
    fun vote(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
        @Valid @RequestBody request: NewsVoteRequest,
    ): NewsVoteResponsePayload = service.vote(current, newsId, request)

    @PostMapping("/{newsId}/report")
    fun report(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
        @Valid @RequestBody request: NewsReportRequest,
    ): ResponseEntity<NewsReportResponsePayload> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(service.report(current, newsId, request))

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: CreateNewsRequest,
    ): NewsItemPayload = service.create(current, request)

    @PutMapping("/{newsId}")
    @PreAuthorize("hasRole('ADMIN')")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
        @Valid @RequestBody request: CreateNewsRequest,
    ): NewsItemPayload = service.update(current, newsId, request)

    @DeleteMapping("/{newsId}")
    @PreAuthorize("hasRole('ADMIN')")
    fun delete(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable newsId: String,
    ): ResponseEntity<Void> {
        service.delete(current, newsId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}

/** Staff view of all articles (drafts included). */
@RestController
@RequestMapping("/admin/news")
@PreAuthorize("hasRole('ADMIN')")
class NewsAdminController(private val service: NewsService) {

    @GetMapping
    fun list(): List<NewsItemPayload> = service.listAllForStaff()
}
