package com.afrithecus.brainbox.api.dashboard.web

import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.dashboard.DashboardService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Student dashboard (BACKEND_BLUEPRINT §2). */
@RestController
@RequestMapping("/dashboard")
class DashboardController(
    private val service: DashboardService,
    private val userRepository: UserRepository,
) {

    @GetMapping("/assignments")
    fun assignments(@AuthenticationPrincipal current: CurrentUser): List<AssignmentPayload> =
        service.assignments(user(current))

    @GetMapping("/contests")
    fun contests(@AuthenticationPrincipal current: CurrentUser): List<ContestPayload> =
        service.contests(user(current))

    @GetMapping("/insights")
    fun insights(@AuthenticationPrincipal current: CurrentUser): DashboardInsightsPayload =
        service.insights(current, user(current))

    @GetMapping("/quick-actions")
    fun quickActions(): List<QuickActionPayload> = service.quickActions()

    private fun user(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
}
