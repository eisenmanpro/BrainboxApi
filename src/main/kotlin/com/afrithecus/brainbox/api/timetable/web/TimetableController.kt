package com.afrithecus.brainbox.api.timetable.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.ScheduleChangeService
import com.afrithecus.brainbox.api.timetable.TimetableService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Teacher timetable, Kenyan export and schedule changes (docs/ongoing). */
@RestController
@RequestMapping("/teacher/timetable")
class TimetableController(
    private val service: TimetableService,
    private val scheduleChanges: ScheduleChangeService,
    private val userRepository: UserRepository,
) {
    private fun teacher(current: CurrentUser) =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }

    @GetMapping
    fun timetable(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TimetableEntryPayload> = service.ownEntries(teacher(currentUser))

    @GetMapping("/kenyan")
    fun kenyan(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TimetableEntryPayload> = service.kenyanTimetable(teacher(currentUser))

    @PostMapping("/auto-schedule")
    fun autoSchedule(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<TimetableEntryPayload> = service.autoSchedule(teacher(currentUser))

    @PostMapping("/entries")
    fun createEntry(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody entry: TimetableEntryPayload,
    ): TimetableEntryPayload = service.createEntry(teacher(currentUser), entry)

    @PutMapping("/entries/{entryId}")
    fun updateEntry(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable entryId: String,
        @RequestBody entry: TimetableEntryPayload,
    ): TimetableEntryPayload = service.updateEntry(teacher(currentUser), entryId, entry)

    @DeleteMapping("/entries/{entryId}")
    fun deleteEntry(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable entryId: String,
    ): ResponseEntity<Void> {
        service.deleteEntry(teacher(currentUser), entryId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/schedule-change")
    fun submitScheduleChange(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: ScheduleChangePayload,
    ): ScheduleChangePayload = scheduleChanges.submit(teacher(currentUser), request)

    @GetMapping("/schedule-changes")
    fun scheduleChanges(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) teacherId: String?,
    ): List<ScheduleChangePayload> = scheduleChanges.list(teacher(currentUser), teacherId)

    @PostMapping("/schedule-changes/{id}/approve")
    fun approveScheduleChange(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ScheduleChangePayload = scheduleChanges.approve(teacher(currentUser), id)

    @PostMapping("/schedule-changes/{id}/reject")
    fun rejectScheduleChange(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ScheduleChangePayload = scheduleChanges.reject(teacher(currentUser), id)
}
