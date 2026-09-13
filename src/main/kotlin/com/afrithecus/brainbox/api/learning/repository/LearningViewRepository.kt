package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.LearningViewEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LearningViewRepository : JpaRepository<LearningViewEntity, UUID> {

    fun findByUserIdAndPostIdAndDayKey(userId: UUID, postId: UUID, dayKey: String): LearningViewEntity?

    fun countByPostId(postId: UUID): Long

    fun findAllByUserId(userId: UUID): List<LearningViewEntity>
}
