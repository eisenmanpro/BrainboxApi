package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.teacher.TeacherChangeRequestService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The school admin's approval gate for teacher changes (N3). The queue also
 * shows requests from teachers who are leaving this school, so a departure is
 * visible even though only the destination school's admin can decide it.
 */
@RestController
@RequestMapping("/admin/schools")
@PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
class AdminTeacherChangeRequestController(
    private val service: TeacherChangeRequestService,
) {

    @GetMapping("/{schoolId}/teacher-change-requests")
    fun requests(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): List<TeacherChangeRequestPayload> = service.forSchool(current, schoolId)

    @PostMapping("/{schoolId}/teacher-change-requests/{requestId}/approve")
    fun approve(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable requestId: String,
        @RequestBody(required = false) request: TeacherChangeDecisionRequest?,
    ): TeacherChangeRequestPayload = service.approve(current, schoolId, requestId, request?.note)

    @PostMapping("/{schoolId}/teacher-change-requests/{requestId}/reject")
    fun reject(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable requestId: String,
        @RequestBody(required = false) request: TeacherChangeDecisionRequest?,
    ): TeacherChangeRequestPayload = service.reject(current, schoolId, requestId, request?.note)
}
