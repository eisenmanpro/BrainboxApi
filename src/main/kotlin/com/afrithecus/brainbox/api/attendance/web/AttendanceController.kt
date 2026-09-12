package com.afrithecus.brainbox.api.attendance.web

import com.afrithecus.brainbox.api.attendance.AttendanceService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Teacher attendance register (doc 04 §4). Register reads and writes are
 * day-bucketed and idempotent per (class, day, student).
 */
@RestController
@RequestMapping("/teacher")
class AttendanceController(
    private val service: AttendanceService,
) {

    @GetMapping("/classes/{classId}/attendance")
    fun getRegister(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestParam date: Long,
    ): List<AttendanceRecordPayload> = service.register(currentUser, classId, date)

    @PostMapping("/attendance")
    fun submitRegister(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody records: List<AttendanceRecordPayload>,
    ): List<AttendanceRecordPayload> = service.submit(currentUser, records)

    @GetMapping("/classes/{classId}/attendance/analytics")
    fun analytics(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestParam(required = false) teacherId: String?,
        @RequestParam(required = false) schoolId: String?,
    ): AttendanceAnalyticsPayload {
        val now = System.currentTimeMillis()
        return service.analytics(currentUser, classId, now - DEFAULT_ANALYTICS_DAYS * DAY_MILLIS, now)
    }

    @GetMapping("/classes/{classId}/attendance/analytics/range")
    fun analyticsRange(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestParam startDate: Long,
        @RequestParam endDate: Long,
    ): AttendanceAnalyticsPayload = service.analytics(currentUser, classId, startDate, endDate)

    @GetMapping("/classes/{classId}/attendance/performance")
    fun performance(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestParam startDate: Long,
        @RequestParam endDate: Long,
    ): AttendancePerformanceAnalyticsPayload = service.performance(currentUser, classId, startDate, endDate)

    @PostMapping("/classes/{classId}/attendance/auto-mark")
    fun autoMark(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable classId: String,
        @RequestParam liveClassId: String,
        @RequestParam date: Long,
    ): List<AttendanceRecordPayload> = service.autoMark(currentUser, classId, liveClassId, date)

    private companion object {
        const val DEFAULT_ANALYTICS_DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
