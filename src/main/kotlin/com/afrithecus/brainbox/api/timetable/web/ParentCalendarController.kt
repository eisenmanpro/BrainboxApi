package com.afrithecus.brainbox.api.timetable.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.timetable.ParentCalendarService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** A linked child's calendar (docs/ongoing/api_timetable_changes.md). */
@RestController
@RequestMapping("/parent")
class ParentCalendarController(private val service: ParentCalendarService) {

    @GetMapping("/child/{childId}/calendar")
    fun calendar(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable childId: String,
    ): List<CalendarEventPayload> = service.calendar(currentUser, childId)
}
