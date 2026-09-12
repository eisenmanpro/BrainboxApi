package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.LearningContentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LearningContentRepository : JpaRepository<LearningContentEntity, UUID> {

    fun findAllByPostIdOrderByOrderIndexAsc(postId: UUID): List<LearningContentEntity>

    fun findAllByPostIdIn(postIds: Collection<UUID>): List<LearningContentEntity>
}
