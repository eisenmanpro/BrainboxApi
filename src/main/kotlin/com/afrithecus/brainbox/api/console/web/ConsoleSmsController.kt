package com.afrithecus.brainbox.api.console.web

import com.afrithecus.brainbox.api.attendance.AttendanceSmsAdminService
import com.afrithecus.brainbox.api.attendance.web.AttendanceSmsDispatchView
import com.afrithecus.brainbox.api.attendance.web.AttendanceSmsSchoolSummary
import com.afrithecus.brainbox.api.attendance.web.UpdateAttendanceSmsBudgetRequest
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

/**
 * Platform console: every school's attendance SMS allocation and spend (D5), and the ledger behind
 * a support question. Reads need SCHOOLS_READ, setting an allocation needs SCHOOLS_MANAGE; both are
 * enforced in the service so the capability model stays in one place.
 */
@RestController
@RequestMapping("/admin/console/sms")
@PreAuthorize("hasRole('ADMIN')")
class ConsoleSmsController(
    private val service: AttendanceSmsAdminService,
) {

    @GetMapping("/schools")
    fun schools(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) term: String?,
    ): List<AttendanceSmsSchoolSummary> = service.consoleSchools(current, term)

    @GetMapping("/schools/{schoolId}")
    fun school(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestParam(required = false) term: String?,
    ): AttendanceSmsSchoolSummary = service.consoleSchool(current, schoolId, term)

    @PostMapping("/schools/{schoolId}/budget")
    fun updateBudget(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestBody request: UpdateAttendanceSmsBudgetRequest,
        @RequestParam(required = false) term: String?,
    ): AttendanceSmsSchoolSummary = service.updateBudget(current, schoolId, request.budgetKes, term)

    @GetMapping("/schools/{schoolId}/dispatches")
    fun dispatches(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestParam(required = false) term: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<AttendanceSmsDispatchView> = service.dispatchesForSchool(current, schoolId, term, limit)
}
