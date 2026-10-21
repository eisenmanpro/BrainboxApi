package com.afrithecus.brainbox.api.contests.web

import com.afrithecus.brainbox.api.contests.admin.ContestAuthoringService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RestController

/**
 * Teacher contest authoring (F4).
 *
 * Contests are grade-level: a teacher authors for one grade of their own school (the school always
 * comes from the token, never the body). Create is a DRAFT; publish is a separate step; finalise
 * closes the window; results publishing fans out to registrants.
 */
@RestController
@RequestMapping("/teacher/contests")
@PreAuthorize("hasRole('TEACHER')")
class TeacherContestController(private val authoring: ContestAuthoringService) {

    @GetMapping
    fun list(@AuthenticationPrincipal currentUser: CurrentUser): List<ContestPayload> =
        authoring.listTeacher(currentUser.userId)

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ContestPayload = authoring.getTeacher(id, currentUser.userId)

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreateContestRequest,
    ): ContestPayload = authoring.createTeacher(request, currentUser.userId)

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
        @Valid @RequestBody request: CreateContestRequest,
    ): ContestPayload = authoring.updateTeacher(id, request, currentUser.userId)

    @PostMapping("/{id}/publish")
    fun publish(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ContestPayload = authoring.publishTeacher(id, currentUser.userId)

    @PostMapping("/{id}/finalise")
    fun finalise(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ContestPayload = authoring.finaliseTeacher(id, currentUser.userId)

    @DeleteMapping("/{id}")
    fun cancel(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        authoring.cancelTeacher(id, currentUser.userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/participants")
    fun participants(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): List<ContestParticipantPayload> = authoring.participantsTeacher(id, currentUser.userId)

    @PostMapping("/{id}/results/publish")
    fun publishResults(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ContestPayload = authoring.publishResultsTeacher(id, currentUser.userId)
}
