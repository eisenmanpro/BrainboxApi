package com.afrithecus.brainbox.api.cbc.web

import com.afrithecus.brainbox.api.cbc.CbcProjectService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** CBC projects (doc 06 §3): feed, submission, moderation, votes, comments, views. */
@RestController
@RequestMapping("/cbc/projects")
class CbcProjectsController(private val service: CbcProjectService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) gradeBand: String?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) cbcStrand: String?,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(defaultValue = "APPROVED") status: String,
        @RequestParam(defaultValue = "recent") sort: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) search: String?,
    ): ProjectListResponsePayload = service.list(current, gradeBand, subject, cbcStrand, schoolId, status, sort, page, limit, search)

    @GetMapping("/featured")
    fun featured(@RequestParam(defaultValue = "5") limit: Int): List<CbcProjectPayload> = service.featured(limit)

    @GetMapping("/mine")
    fun mine(@AuthenticationPrincipal current: CurrentUser): List<CbcProjectPayload> = service.mine(current)

    @GetMapping("/{projectId}")
    fun detail(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable projectId: String,
    ): CbcProjectPayload = service.detail(current, projectId)

    @PostMapping
    fun submit(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: SubmitCbcProjectRequest,
    ): CbcProjectPayload = service.submit(current, request)

    @PatchMapping("/{projectId}/status")
    fun updateStatus(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable projectId: String,
        @Valid @RequestBody request: UpdateProjectStatusRequest,
    ): CbcProjectPayload = service.updateStatus(current, projectId, request)

    @PostMapping("/{projectId}/vote")
    fun vote(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable projectId: String,
        @Valid @RequestBody request: CbcVoteRequest,
    ): ProjectVotePayload = service.vote(current, projectId, request)

    @DeleteMapping("/{projectId}/vote")
    fun removeVote(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable projectId: String,
    ): ResponseEntity<Void> {
        service.removeVote(current, projectId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @GetMapping("/{projectId}/comments")
    fun comments(
        @PathVariable projectId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CommentListResponsePayload = service.comments(projectId, page, limit)

    @PostMapping("/{projectId}/comments")
    fun addComment(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable projectId: String,
        @Valid @RequestBody request: AddProjectCommentRequest,
    ): ProjectCommentPayload = service.addComment(current, projectId, request)

    @PostMapping("/{projectId}/view")
    fun recordView(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable projectId: String,
    ): ResponseEntity<Void> {
        service.recordView(current, projectId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
