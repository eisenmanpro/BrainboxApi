package com.afrithecus.brainbox.api.parent.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.parent.ParentInsightsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The parent dashboard's data, computed from the school's own records for a linked child
 * (`api_parent_portal_changes.md`). Every route authorizes the link before it reads.
 */
@RestController
@RequestMapping("/parent")
class ParentInsightsController(private val service: ParentInsightsService) {

    /**
     * The guardian's linked children side by side. Deliberately not child-scoped: it is
     * the one read that spans every child the caller is linked to, and it never returns
     * a child they are not linked to.
     */
    @GetMapping("/family/comparison")
    fun familyComparison(@AuthenticationPrincipal current: CurrentUser): FamilyComparisonPayload =
        service.familyComparison(current)

    @GetMapping("/child/{childId}/performance")
    fun performance(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<SubjectPerformancePayload> = service.performance(current, childId)

    @GetMapping("/child/{childId}/achievements")
    fun achievements(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload =
        service.achievements(current, childId)

    @GetMapping("/child/{childId}/recommendations")
    fun recommendations(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<ParentRecommendationPayload> = service.recommendations(current, childId)

    @GetMapping("/child/{childId}/contract")
    fun contract(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): com.afrithecus.brainbox.api.contract.web.LearningContractPayload = service.contract(current, childId)

    @GetMapping("/child/{childId}/stats")
    fun stats(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): ChildStatsPayload = service.stats(current, childId)

    @GetMapping("/alerts/{childId}")
    fun alerts(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<ParentAlertPayload> = service.alerts(current, childId)

    @GetMapping("/child/{childId}/activities")
    fun activities(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<RecentActivityPayload> = service.activities(current, childId)

    @GetMapping("/child/{childId}/weekly-performance")
    fun weeklyPerformance(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): WeeklyPerformancePayload = service.weeklyPerformance(current, childId)

    @GetMapping("/child/{childId}/upcoming-events")
    fun upcomingEvents(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<UpcomingEventPayload> = service.upcomingEvents(current, childId)

    @GetMapping("/child/{childId}/homework")
    fun homework(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<HomeworkItemPayload> = service.homework(current, childId)

    @GetMapping("/child/{childId}/engagement")
    fun engagement(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): EngagementPayload = service.engagement(current, childId)

    @GetMapping("/child/{childId}/cbc-ratings")
    fun cbcRatings(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable childId: String,
    ): List<CbcStrandRatingPayload> = service.cbcRatings(current, childId)
}
