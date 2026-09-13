package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.SchoolRegistrationService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Admin review of queued school registration requests (doc 08). */
@RestController
@RequestMapping("/admin/school-registration-requests")
@PreAuthorize("hasRole('ADMIN')")
class AdminSchoolRegistrationController(private val service: SchoolRegistrationService) {

    @GetMapping
    fun list(@RequestParam(required = false) status: String?): List<SchoolRegistrationRequestView> =
        service.list(status)

    @PostMapping("/{id}/approve")
    fun approve(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable id: String,
        @RequestBody(required = false) request: SchoolRegistrationReviewRequest?,
    ): SchoolRegistrationRequestView = service.approve(current.userId, id, request?.note)

    @PostMapping("/{id}/reject")
    fun reject(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable id: String,
        @RequestBody(required = false) request: SchoolRegistrationReviewRequest?,
    ): SchoolRegistrationRequestView = service.reject(current.userId, id, request?.note)
}
