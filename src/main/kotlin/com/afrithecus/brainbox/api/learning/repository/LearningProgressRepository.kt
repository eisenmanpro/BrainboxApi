package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.LearningProgressEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LearningProgressRepository : JpaRepository<LearningProgressEntity, UUID> {

    fun findByUserIdAndPostId(userId: UUID, postId: UUID): LearningProgressEntity?

    fun findAllByUserId(userId: UUID): List<LearningProgressEntity>
}
