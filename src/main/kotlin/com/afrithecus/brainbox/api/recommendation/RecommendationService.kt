package com.afrithecus.brainbox.api.recommendation

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.ContentScope
import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.LearningProgressRepository
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import com.afrithecus.brainbox.api.recommendation.entity.RecommendationInteractionEntity
import com.afrithecus.brainbox.api.recommendation.repository.RecommendationInteractionRepository
import com.afrithecus.brainbox.api.recommendation.web.RecommendationDto
import com.afrithecus.brainbox.api.recommendation.web.RecommendationInteractionRequest
import com.afrithecus.brainbox.api.recommendation.web.RecommendationsResponseDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Server-derived recommendation rail (docs/ongoing/api_recommendations_changes.md):
 * continue-learning and weak-topic signals from progress/mastery, collaborative
 * signal from uploaded interactions, and school trending. A successful response is
 * authoritative for the client.
 */
@Service
class RecommendationService(
    private val postRepository: LearningPostRepository,
    private val progressRepository: LearningProgressRepository,
    private val masteryRepository: TopicMasteryRepository,
    private val interactionRepository: RecommendationInteractionRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun forUser(current: CurrentUser, userIdRaw: String, limit: Int): RecommendationsResponseDto {
        val user = requireUser(current)
        if (userIdRaw.isNotBlank() && userIdRaw != user.id.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot read another user's recommendations")
        }
        val cap = limit.coerceIn(1, MAX_LIMIT)
        val visible = visiblePosts(user)
        val postById = visible.associateBy { it.id }
        val trending = trendingFor(user, TRENDING_LIMIT)
        val progress = progressRepository.findAllByUserId(user.id)
        val completed = progress.filter { it.completed }.map { it.postId }
        val chosen = LinkedHashMap<UUID, RecommendationDto>()

        // Continue where the learner left off.
        progress.filter { !it.completed }.sortedByDescending { it.lastViewedAt }.forEach { row ->
            postById[row.postId]?.let { post ->
                chosen.putIfAbsent(post.id, item(post, "CONTINUE_LEARNING", "HIGH", "Continue where you left off", 0.9f))
            }
        }

        // Weak topic mastery drives topic-based picks.
        masteryRepository.findAllByUserIdOrderByScoreAsc(user.id)
            .filter { it.score < WEAK_THRESHOLD }
            .forEach { mastery ->
                visible.filter { post ->
                    post.id !in completed && post.id !in chosen.keys &&
                        (post.subject.equals(mastery.subject, ignoreCase = true) ||
                            post.cbcStrand?.equals(mastery.topicName, ignoreCase = true) == true)
                }.sortedByDescending { it.viewCount }.take(2).forEach { post ->
                    val confidence = (1.0 - mastery.score / 100.0).coerceIn(0.3, 0.95).toFloat()
                    chosen.putIfAbsent(
                        post.id,
                        item(
                            post,
                            "TOPIC_BASED",
                            if (mastery.score < 35) "HIGH" else "MEDIUM",
                            "You scored " + mastery.score.toInt() + "% on " + mastery.topicName + ". Review the basics.",
                            confidence,
                        ),
                    )
                }
            }

        // Learners in the same school who engaged with posts this learner has not.
        user.schoolId?.let { schoolId ->
            val mine = interactionRepository.findAllByUserId(user.id).map { it.postId }.toSet()
            interactionRepository.findAllBySchoolIdAndOccurredAtAfter(schoolId, clock.instant().minus(PEER_WINDOW))
                .filter { it.userId != user.id }
                .groupBy { it.postId }
                .mapValues { entry -> entry.value.sumOf { it.interactionScore } }
                .entries.sortedByDescending { it.value }.take(10)
                .forEach { (postId, score) ->
                    val post = postById[postId] ?: return@forEach
                    if (post.id in completed || post.id in chosen.keys || post.id in mine) return@forEach
                    chosen.putIfAbsent(
                        post.id,
                        item(post, "COLLABORATIVE", "MEDIUM", "Learners like you studied this", (score / 300.0).coerceIn(0.3, 0.95).toFloat()),
                    )
                }
        }

        // Trending fills any remaining slots without displacing a personalized pick.
        trending.forEach { trend ->
            val postId = runCatching { UUID.fromString(trend.postId) }.getOrNull() ?: return@forEach
            if (postId !in completed && postId !in chosen.keys) chosen[postId] = trend
        }

        val recommendations = chosen.values
            .sortedWith(compareBy<RecommendationDto> { priorityRank(it.priority) }.thenByDescending { it.confidenceScore })
            .take(cap)
        return RecommendationsResponseDto(recommendations, trending, clock.millis())
    }

    @Transactional(readOnly = true)
    fun trending(current: CurrentUser, schoolIdRaw: String?, limit: Int): List<RecommendationDto> {
        val user = requireUser(current)
        if (!schoolIdRaw.isNullOrBlank()) {
            val requested = runCatching { UUID.fromString(schoolIdRaw.trim()) }.getOrNull()
            if (requested != null && user.schoolId != null && requested != user.schoolId && user.role != Role.ADMIN) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Not your school")
            }
        }
        return trendingFor(user, limit.coerceIn(1, MAX_LIMIT))
    }

    @Transactional
    fun recordInteraction(current: CurrentUser, request: RecommendationInteractionRequest) {
        val user = requireUser(current)
        val postId = runCatching { UUID.fromString(request.postId.trim()) }.getOrNull() ?: return
        if (postRepository.findById(postId).isEmpty) return
        val occurredAt = request.timestamp?.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: clock.instant()
        if (interactionRepository.findAllByUserIdAndPostIdAndOccurredAt(user.id, postId, occurredAt) != null) return
        interactionRepository.save(
            RecommendationInteractionEntity().apply {
                userId = user.id
                this.postId = postId
                interactionType = request.interactionType.trim().ifEmpty { "view" }.take(32)
                timeSpentSeconds = request.timeSpentSeconds
                interactionScore = request.interactionScore.coerceAtLeast(0.0)
                this.occurredAt = occurredAt
                schoolId = user.schoolId
            }
        )
    }

    // ------------------------------------------------------------ internals

    private fun trendingFor(user: UserEntity, limit: Int): List<RecommendationDto> {
        val visible = visiblePosts(user)
        val postById = visible.associateBy { it.id }
        val scores: Map<UUID, Double> = user.schoolId?.let { schoolId ->
            interactionRepository.findAllBySchoolIdAndOccurredAtAfter(schoolId, clock.instant().minus(TREND_WINDOW))
                .groupBy { it.postId }
                .mapValues { entry -> entry.value.sumOf { it.interactionScore } }
        }.orEmpty()
        val ranked: List<Pair<LearningPostEntity, Double>> = if (scores.isNotEmpty()) {
            scores.entries.filter { postById.containsKey(it.key) }
                .sortedByDescending { it.value }
                .take(limit)
                .map { postById.getValue(it.key) to it.value }
        } else {
            visible.sortedByDescending { it.viewCount }.take(limit).map { it to it.viewCount.toDouble() }
        }
        val max = ranked.maxOfOrNull { it.second } ?: 0.0
        return ranked.map { (post, score) ->
            item(
                post,
                "TRENDING",
                "MEDIUM",
                "Most studied in your school this week",
                if (max <= 0.0) 0.5f else (score / max).coerceIn(0.3, 0.95).toFloat(),
            )
        }
    }

    private fun visiblePosts(user: UserEntity): List<LearningPostEntity> =
        postRepository.findAllByIsPublishedTrue()
            .filter { ContentScope.isVisible(it.scope, it.schoolId, it.gradeLevel, it.teacherId, user) }

    private fun item(
        post: LearningPostEntity,
        type: String,
        priority: String,
        reason: String,
        confidence: Float,
    ) = RecommendationDto(
        id = type.lowercase() + "_" + post.id,
        type = type,
        priority = priority,
        postId = post.id.toString(),
        postTitle = post.title,
        postSubject = post.subject,
        reason = reason,
        confidenceScore = confidence,
        imageUrl = post.imageUrl,
    )

    private fun priorityRank(priority: String): Int = when (priority.uppercase()) {
        "HIGH" -> 0
        "MEDIUM" -> 1
        else -> 2
    }

    private fun requireUser(current: CurrentUser): UserEntity =
        userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")

    private companion object {
        const val MAX_LIMIT = 50
        const val TRENDING_LIMIT = 5
        const val WEAK_THRESHOLD = 50.0
        val TREND_WINDOW: Duration = Duration.ofDays(7)
        val PEER_WINDOW: Duration = Duration.ofDays(30)
    }
}
