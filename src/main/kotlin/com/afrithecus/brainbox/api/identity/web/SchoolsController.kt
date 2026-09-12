package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.SchoolDirectoryService
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

/**
 * Public school directory (docs/ongoing/api_schools_changes.md): reads are
 * unauthenticated; reviews/join-requests/reports require a session and derive
 * the author server-side.
 */
@RestController
@RequestMapping("/schools")
class SchoolsController(private val service: SchoolDirectoryService) {

    @GetMapping("/search")
    fun search(@RequestParam query: String): List<SchoolListPayload> = service.search(query)

    @GetMapping("/all")
    fun all(): List<SchoolListPayload> = service.list()

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String): SchoolDetailPayload = service.detail(id)

    @PostMapping("/{id}/reviews")
    fun review(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable id: String,
        @Valid @RequestBody request: SchoolReviewRequest,
    ): ResponseEntity<SchoolReviewPayload> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.submitReview(current, id, request))

    @PostMapping("/{id}/join-requests")
    fun joinRequest(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable id: String,
        @Valid @RequestBody request: JoinSchoolRequestPayload,
    ): ResponseEntity<SubmissionResultPayload> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.submitJoinRequest(current, id, request))

    @PostMapping("/{id}/reports")
    fun report(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable id: String,
        @Valid @RequestBody request: SchoolReportRequestPayload,
    ): ResponseEntity<SubmissionResultPayload> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.submitReport(current, id, request))
}
