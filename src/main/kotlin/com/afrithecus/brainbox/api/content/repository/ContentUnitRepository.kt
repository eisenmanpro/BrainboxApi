package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContentUnitRepository : JpaRepository<ContentUnitEntity, UUID> {

    fun findByGenerationKey(generationKey: String): ContentUnitEntity?

    fun findAllByConceptIdAndGradeLevelAndTaskTypeAndReviewState(
        conceptId: UUID,
        gradeLevel: String,
        taskType: String,
        reviewState: String,
    ): List<ContentUnitEntity>
}
