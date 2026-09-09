package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LearningPostRepository : JpaRepository<LearningPostEntity, UUID> {

    fun findAllByIsPublishedTrue(): List<LearningPostEntity>
}
