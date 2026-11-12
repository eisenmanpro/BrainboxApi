package com.afrithecus.brainbox.api.attendance.web

import com.afrithecus.brainbox.api.attendance.AttendanceSmsAdminService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * P3.6/P6.2: the school panel's attendance SMS surface. The ICT admin sees the term allocation,
 * spend and recent ledger for their own school; the platform ADMIN may pass a schoolId.
 */
@RestController
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
class AttendanceSmsController(
    private val service: AttendanceSmsAdminService,
) {

    @GetMapping("/school/attendance-sms/summary")
    fun summary(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) schoolId: String?,
    ): AttendanceSmsSchoolSummary = service.schoolSummary(current, schoolId)

    @GetMapping("/school/attendance-sms/dispatches")
    fun dispatches(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<AttendanceSmsDispatchView> = service.schoolDispatches(current, schoolId, limit)
}
