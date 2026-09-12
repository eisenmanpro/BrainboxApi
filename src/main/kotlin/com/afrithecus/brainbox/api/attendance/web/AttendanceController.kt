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
}
