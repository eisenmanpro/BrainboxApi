package com.afrithecus.brainbox.api.achievements.web

import com.afrithecus.brainbox.api.achievements.AchievementsService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Achievements, XP, badges, leaderboards and rewards (doc 03 §7/§8). */
@RestController
@RequestMapping("/achievements")
class AchievementsController(private val service: AchievementsService) {

    @GetMapping("/user/{userId}")
    fun userAchievements(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): UserAchievementsPayload = service.userAchievements(current, userId)

    @GetMapping("/user/{userId}/contest-history")
    fun contestHistory(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): List<ContestHistoryEntryPayload> = service.contestHistory(current, userId)

    @GetMapping("/leaderboard")
    fun leaderboard(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(defaultValue = "national") type: String,
        @RequestParam(defaultValue = "100") limit: Int,
    ): LeaderboardResponsePayload = service.leaderboard(current, type, limit)

    @GetMapping("/user/{userId}/mastery-tree")
    fun masteryTree(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
    ): MasteryTreePayload = service.masteryTree(current, userId)

    @GetMapping("/rewards")
    fun rewards(@AuthenticationPrincipal current: CurrentUser): List<RewardItemPayload> = service.rewards(current)

    @PostMapping("/rewards/redeem")
    fun redeem(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam rewardId: String,
    ): RewardItemPayload = service.redeem(current, userId, rewardId)

    @PostMapping("/xp/award")
    fun awardXp(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam amount: Int,
        @RequestParam(defaultValue = "GENERAL") activityType: String,
    ): UserAchievementsPayload = service.awardXp(current, userId, amount, activityType)

    @PostMapping("/badges/unlock")
    fun unlockBadge(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam userId: String,
        @RequestParam badgeId: String,
    ): UserAchievementsPayload = service.unlockBadge(current, userId, badgeId)
}
