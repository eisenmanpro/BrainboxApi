package com.afrithecus.brainbox.api.leaderboard.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.leaderboard.LeaderboardService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Admin leaderboard management (doc 08 §7). Viewing is open to a platform ADMIN
 * and to an ICT_ADMIN (own school only); resetting a season and recalculating
 * the XP totals are platform-admin operations.
 */
@RestController
@RequestMapping("/admin/leaderboard")
class AdminLeaderboardController(private val service: LeaderboardService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
    fun leaderboard(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "ALL") timeframe: String?,
        @RequestParam(required = false) grade: String?,
        @RequestParam(required = false) subject: String?,
        @RequestParam(required = false) schoolId: String?,
    ): List<AdminLeaderboardEntryPayload> =
        service.adminLeaderboard(current, limit, timeframe, grade, subject, schoolId)

    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADMIN')")
    fun reset(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(defaultValue = "ALL") timeframe: String?,
    ): ResponseEntity<Void> {
        service.reset(current, timeframe)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/recalculate")
    @PreAuthorize("hasRole('ADMIN')")
    fun recalculate(@AuthenticationPrincipal current: CurrentUser): ResponseEntity<Void> {
        service.recalculate(current)
        return ResponseEntity.noContent().build()
    }
}
