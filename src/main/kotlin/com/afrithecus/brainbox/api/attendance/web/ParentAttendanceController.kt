package com.afrithecus.brainbox.api.attendance.web

import com.afrithecus.brainbox.api.attendance.AttendanceService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Parent attendance surface (doc 07 §5.2): a linked child's register and the
 * server-computed attendance/performance intelligence. Ownership is enforced.
 */
@RestController
@RequestMapping("/parent")
class ParentAttendanceController(
    private val service: AttendanceService,
) {

    @GetMapping("/child/{childId}/attendance")
    fun attendance(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable childId: String,
    ): List<ParentAttendanceRecordPayload> = service.parentAttendance(currentUser, childId)

    @GetMapping("/child/{childId}/attendance/performance")
    fun performance(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable childId: String,
    ): ChildAttendancePerformancePayload = service.parentPerformance(currentUser, childId)
}
