package com.afrithecus.brainbox.api.recommendation.web

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningProgressService
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

/**
 * Recommendation rail (doc 14 §3): per-user recommendations derived from the
 * learning progress service, and school trending posts by view count. The client
 * re-ranks on device; an empty list simply hides the rail.
 */
@RestController
@RequestMapping("/recommendations")
class RecommendationsController(
    private val progressService: LearningProgressService,
    private val postRepository: LearningPostRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @GetMapping("/user/{userId}")
    fun forUser(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable userId: String,
        @RequestParam(defaultValue = "10") limit: Int,
    ): RecommendationsResponseDto {
        if (userId != currentUser.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot read another user's recommendations")
        }
        val user = userRepository.findById(currentUser.userId).orElseThrow { notFound("User not found") }
        val items = progressService.recommendations(user).take(limit.coerceIn(1, 50)).map { payload ->
            RecommendationDto(
                id = payload.postId,
                type = "TOPIC_BASED",
                priority = payload.priority,
                postId = payload.postId,
                postTitle = payload.title,
                postSubject = payload.subject,
                reason = payload.reason,
                confidenceScore = when (payload.priority) {
                    "HIGH" -> 0.9f
                    "MEDIUM" -> 0.7f
                    else -> 0.5f
                },
            )
        }
        return RecommendationsResponseDto(items, clock.millis())
    }

    @GetMapping("/trending")
    fun trending(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(defaultValue = "5") limit: Int,
    ): List<RecommendationDto> {
        val user = userRepository.findById(currentUser.userId).orElseThrow { notFound("User not found") }
        val scopeId = schoolId?.takeIf { it.isNotBlank() } ?: user.schoolId?.toString()
        return postRepository.findAllByIsPublishedTrue()
            .filter { it.scope.name == "GLOBAL" || it.schoolId == null || it.schoolId.toString() == scopeId }
            .sortedByDescending { it.viewCount }
            .take(limit.coerceIn(1, 20))
            .map { post ->
                RecommendationDto(
                    id = post.id.toString(),
                    type = "TRENDING",
                    priority = "LOW",
                    postId = post.id.toString(),
                    postTitle = post.title,
                    postSubject = post.subject,
                    reason = "Popular with learners in your school",
                    confidenceScore = 0.6f,
                    imageUrl = post.imageUrl,
                )
            }
    }
}
