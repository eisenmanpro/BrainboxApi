package com.afrithecus.brainbox.api.cbc.web

import com.afrithecus.brainbox.api.cbc.CbcProjectService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Public CBC project browse flow (docs/ongoing/api_cbc_public_changes.md).
 * No auth; anonymous mutations carry a stable device id in X-Guest-Id.
 * Only APPROVED/FEATURED projects are ever exposed.
 */
@RestController
@RequestMapping("/cbc/public/projects")
class CbcPublicController(private val service: CbcProjectService) {

    @GetMapping
    fun list(
        @RequestHeader(value = "X-Guest-Id", required = false) guestId: String?,
        @RequestParam(required = false) gradeBand: String?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) cbcStrand: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "recent") sort: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
    ): ProjectListResponsePayload = service.listPublic(guestId, gradeBand, subject, cbcStrand, search, sort, page, limit)

    @GetMapping("/featured")
    fun featured(
        @RequestHeader(value = "X-Guest-Id", required = false) guestId: String?,
        @RequestParam(defaultValue = "6") limit: Int,
    ): List<CbcProjectPayload> = service.featuredPublic(guestId, limit)

    @GetMapping("/{projectId}")
    fun detail(
        @RequestHeader(value = "X-Guest-Id", required = false) guestId: String?,
        @PathVariable projectId: String,
    ): CbcProjectPayload = service.detailPublic(guestId, projectId)

    @PostMapping("/{projectId}/vote")
    fun vote(
        @RequestHeader("X-Guest-Id") guestId: String,
        @PathVariable projectId: String,
        @Valid @RequestBody request: CbcVoteRequest,
    ): PublicVoteResponsePayload = service.votePublic(guestId, projectId, request)

    @DeleteMapping("/{projectId}/vote")
    fun removeVote(
        @RequestHeader("X-Guest-Id") guestId: String,
        @PathVariable projectId: String,
    ): PublicVoteResponsePayload = service.removeVotePublic(guestId, projectId)

    @GetMapping("/{projectId}/comments")
    fun comments(
        @PathVariable projectId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CommentListResponsePayload = service.commentsPublic(projectId, page, limit)

    @PostMapping("/{projectId}/comments")
    fun addComment(
        @RequestHeader("X-Guest-Id") guestId: String,
        @PathVariable projectId: String,
        @Valid @RequestBody request: PublicCommentRequestPayload,
    ): ProjectCommentPayload = service.addCommentPublic(guestId, projectId, request)

    @PostMapping("/{projectId}/track")
    fun track(
        @RequestHeader("X-Guest-Id") guestId: String,
        @PathVariable projectId: String,
        @Valid @RequestBody request: PublicTrackRequestPayload,
    ): PublicTrackResponsePayload = service.trackPublic(guestId, projectId, request)

    @PostMapping("/{projectId}/report")
    fun report(
        @RequestHeader("X-Guest-Id") guestId: String,
        @PathVariable projectId: String,
        @Valid @RequestBody request: PublicReportRequestPayload,
    ): PublicReportResponsePayload = service.reportPublic(guestId, projectId, request)

    @PostMapping("/{projectId}/view")
    fun recordView(
        @RequestHeader(value = "X-Guest-Id", required = false) guestId: String?,
        @PathVariable projectId: String,
    ): ResponseEntity<Void> {
        service.recordViewPublic(guestId, projectId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
