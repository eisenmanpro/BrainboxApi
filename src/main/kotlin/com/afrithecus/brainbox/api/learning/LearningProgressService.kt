package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.learning.entity.LearningProgressEntity
import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.LearningProgressRepository
import com.afrithecus.brainbox.api.learning.web.ContinueLearningItem
import com.afrithecus.brainbox.api.learning.web.LearningProgressPayload
import com.afrithecus.brainbox.api.learning.web.LearningProgressRequest
import com.afrithecus.brainbox.api.learning.web.RecommendationPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Per-post learning progress, continue-learning and server-derived
 * recommendations (doc 03 §4-§5).
 */
@Service
class LearningProgressService(
    private val progressRepository: LearningProgressRepository,
    private val postRepository: LearningPostRepository,
    private val clock: Clock,
) {

    @Transactional
    fun upsert(user: UserEntity, request: LearningProgressRequest): LearningProgressPayload {
        if (request.quizScore < -1 || request.quizScore > 100) {
            throw invalidArgument("quizScore must be 0-100 or -1 while pending")
        }
        val post = findVisiblePost(user, request.postId)
        val now = clock.instant()
        val lastViewedAt = request.lastViewedAt?.let(Instant::ofEpochMilli) ?: now
        val existing = progressRepository.findByUserIdAndPostId(user.id, post.id)
        if (existing != null && existing.lastViewedAt.isAfter(lastViewedAt)) {
            return toPayload(existing) // keep newest sync (idempotent, doc 03 §4.1)
        }
        val row = (existing ?: LearningProgressEntity().apply {
            this.userId = user.id
            this.postId = post.id
        }).apply {
            quizScore = request.quizScore
            this.lastViewedAt = lastViewedAt
            completed = request.completed
            answersJson = request.answersJson
            updatedAt = now
        }
        progressRepository.save(row)
        return toPayload(row)
    }

    @Transactional(readOnly = true)
    fun continueLearning(user: UserEntity): List<ContinueLearningItem> {
        val posts = visiblePostMap(user)
        return progressRepository.findAllByUserId(user.id)
            .mapNotNull { progress ->
                val post = posts[progress.postId] ?: return@mapNotNull null
                val completed = progress.completed
                val progressPercent = when {
                    completed -> 100
                    progress.quizScore >= 0 -> progress.quizScore
                    else -> 0
                }
                ContinueLearningItem(
                    postId = post.id.toString(),
                    title = post.title,
                    subject = post.subject,
                    progress = progressPercent,
                    lastViewedAt = progress.lastViewedAt.toEpochMilli(),
                    estimatedMinutesRemaining = if (completed) 0 else post.estimatedMinutes,
                )
            }
            .sortedByDescending { it.lastViewedAt }
    }

    @Transactional(readOnly = true)
    fun recommendations(user: UserEntity): List<RecommendationPayload> {
        val visible = visiblePosts(user)
        if (visible.isEmpty()) return emptyList()
        val progressByPost = progressRepository.findAllByUserId(user.id)
            .filter { it.quizScore >= 0 }
            .associate { it.postId to it }
        // Subject affinity from scored progress; fall back to popularity.
        val affinity = progressByPost.values
            .groupingBy { postsByIdentity[it.postId]?.subject ?: "" }
            .eachCount()
        val candidates = visible
            .filter { it.id !in progressByPost.keys }
            .sortedByDescending { post ->
                val bonus = if (affinity.containsKey(post.subject)) affinity[post.subject]!! else 0
                bonus * 10 + post.viewCount
            }
            .take(RECOMMENDATION_LIMIT)

        return candidates.mapIndexed { index, post ->
            val personalized = affinity.containsKey(post.subject)
            RecommendationPayload(
                postId = post.id.toString(),
                title = post.title,
                subject = post.subject,
                reason = if (personalized) {
                    "Because you are making progress in " + post.subject
                } else {
                    "Popular in " + post.subject
                },
                priority = when {
                    index < 3 -> "HIGH"
                    index < 10 -> "MEDIUM"
                    else -> "LOW"
                },
            )
        }
    }

    // ------------------------------------------------------------ internals

    private val postsByIdentity = mutableMapOf<UUID, LearningPostEntity>()

    private fun visiblePosts(user: UserEntity): List<LearningPostEntity> {
        val visible = postRepository.findAllByIsPublishedTrue()
            .filter { post -> ContentScope.isVisible(post.scope, post.schoolId, post.gradeLevel, post.teacherId, user) }
        postsByIdentity.clear()
        visible.forEach { postsByIdentity[it.id] = it }
        return visible
    }

    private fun visiblePostMap(user: UserEntity): Map<UUID, LearningPostEntity> {
        val map = LinkedHashMap<UUID, LearningPostEntity>()
        postRepository.findAllByIsPublishedTrue().forEach { post ->
            if (ContentScope.isVisible(post.scope, post.schoolId, post.gradeLevel, post.teacherId, user)) {
                map[post.id] = post
            }
        }
        return map
    }

    private fun findVisiblePost(user: UserEntity, raw: String): LearningPostEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("post id is not a valid identifier")
        val post = postRepository.findById(id).orElse(null) ?: throw notFound("Post not found")
        if (!post.isPublished || !ContentScope.isVisible(post.scope, post.schoolId, post.gradeLevel, post.teacherId, user)) {
            throw notFound("Post not found")
        }
        return post
    }

    private fun toPayload(row: LearningProgressEntity) = LearningProgressPayload(
        postId = row.postId.toString(),
        quizScore = row.quizScore,
        lastViewedAt = row.lastViewedAt.toEpochMilli(),
        completed = row.completed,
    )

    private companion object {
        const val RECOMMENDATION_LIMIT = 20
    }
}
