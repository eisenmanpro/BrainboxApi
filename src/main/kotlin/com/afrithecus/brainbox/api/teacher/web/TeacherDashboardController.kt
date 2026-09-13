package com.afrithecus.brainbox.api.teacher.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.teacher.TeacherDashboardService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Teacher dashboard payload (docs/ongoing/api_teacher_roster_changes.md). */
@RestController
@RequestMapping("/teacher")
class TeacherDashboardController(private val service: TeacherDashboardService) {

    @GetMapping("/dashboard")
    fun dashboard(@AuthenticationPrincipal current: CurrentUser): TeacherDashboardPayload =
        service.dashboard(current)
}
