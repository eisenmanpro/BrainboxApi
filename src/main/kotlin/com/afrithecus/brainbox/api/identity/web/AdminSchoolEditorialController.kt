package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.SchoolEditorialService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The platform-moderator editorial surface for a school listing (FEAT-01/FEAT-06).
 * ADMIN-only and separate from the school-scoped [AdminSchoolController], so an
 * ICT admin can never feature its own school. Writes are audited server-side.
 */
@RestController
@RequestMapping("/admin/schools")
@PreAuthorize("hasRole('ADMIN')")
class AdminSchoolEditorialController(private val service: SchoolEditorialService) {

    @GetMapping("/{schoolId}/editorial")
    fun read(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): SchoolEditorialPayload = service.read(current.userId, schoolId)

    @PutMapping("/{schoolId}/editorial")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @Valid @RequestBody request: UpdateSchoolEditorialRequest,
    ): SchoolEditorialPayload = service.update(current.userId, schoolId, request)
}
