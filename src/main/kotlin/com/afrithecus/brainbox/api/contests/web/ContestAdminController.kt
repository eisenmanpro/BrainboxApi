package com.afrithecus.brainbox.api.contests.web

import com.afrithecus.brainbox.api.contests.admin.ContestAuthoringService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Contest authoring (ADMIN gate for now). */
@RestController
@RequestMapping("/admin/contests")
@PreAuthorize("hasRole('ADMIN')")
class ContestAdminController(private val authoring: ContestAuthoringService) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: CreateContestRequest,
    ): ContestPayload = authoring.create(request, currentUser.userId)

    @GetMapping
    fun list(): List<ContestPayload> = authoring.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ContestPayload = authoring.get(id)

    @DeleteMapping("/{id}")
    fun cancel(@PathVariable id: String): ResponseEntity<Void> {
        authoring.cancel(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
