package com.afrithecus.brainbox.api.live.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.live.LiveClassService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin authoring for live classes. Teacher-facing authoring arrives with the
 * Phase 3 teacher portal; this is the server-side source of scheduled classes.
 */
@RestController
@RequestMapping("/admin/live-classes")
@PreAuthorize("hasRole('ADMIN')")
class LiveAdminController(private val service: LiveClassService) {

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @Valid @RequestBody request: CreateLiveClassRequest,
    ): LiveClassPayload = service.create(current, request)

    @GetMapping
    fun list(): List<LiveClassPayload> = service.listAll()

    @PostMapping("/{classId}/status")
    fun updateStatus(
        @PathVariable classId: String,
        @Valid @RequestBody request: UpdateLiveClassStatusRequest,
    ): LiveClassPayload = service.updateStatus(classId, request)
}
