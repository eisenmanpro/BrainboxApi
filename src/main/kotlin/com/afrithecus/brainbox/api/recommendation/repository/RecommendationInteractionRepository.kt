package com.afrithecus.brainbox.api.recommendation.repository

import com.afrithecus.brainbox.api.recommendation.entity.RecommendationInteractionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface RecommendationInteractionRepository : JpaRepository<RecommendationInteractionEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<RecommendationInteractionEntity>

    fun findAllBySchoolIdAndOccurredAtAfter(schoolId: UUID, after: Instant): List<RecommendationInteractionEntity>

    fun findAllByUserIdAndPostIdAndOccurredAt(
        userId: UUID,
        postId: UUID,
        occurredAt: Instant,
    ): RecommendationInteractionEntity?
}
