package com.afrithecus.brainbox.api.recommendation.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.recommendation.RecommendationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Recommendation rail (docs/ongoing/api_recommendations_changes.md). The server is
 * the source of truth for personalized and trending picks; the client falls back to
 * its on-device engine only when offline.
 */
@RestController
@RequestMapping("/recommendations")
class RecommendationsController(private val service: RecommendationService) {

    @GetMapping("/user/{userId}")
    fun forUser(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): RecommendationsResponseDto = service.forUser(currentUser, userId, limit)

    @GetMapping("/trending")
    fun trending(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(defaultValue = "5") limit: Int,
    ): List<RecommendationDto> = service.trending(currentUser, schoolId, limit)

    /** Best-effort telemetry; a replay of the same timestamp is ignored. */
    @PostMapping("/interaction")
    fun interaction(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestBody request: RecommendationInteractionRequest,
    ): ResponseEntity<Void> {
        service.recordInteraction(currentUser, request)
        return ResponseEntity.noContent().build()
    }
}
