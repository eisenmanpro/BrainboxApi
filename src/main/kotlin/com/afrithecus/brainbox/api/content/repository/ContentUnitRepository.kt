package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ContentUnitRepository : JpaRepository<ContentUnitEntity, UUID> {

    fun findByGenerationKey(generationKey: String): ContentUnitEntity?

    fun findAllByConceptIdAndGradeLevelAndTaskTypeAndReviewState(
        conceptId: UUID,
        gradeLevel: String,
        taskType: String,
        reviewState: String,
    ): List<ContentUnitEntity>

    /** Review queue: newest-first by creation, optional state/subject/grade filters and a cursor. */
    @Query(
        "SELECT u FROM ContentUnitEntity u WHERE " +
            "(:reviewState IS NULL OR u.reviewState = :reviewState) AND " +
            "(:subject IS NULL OR LOWER(u.subject) = LOWER(:subject)) AND " +
            "(:grade IS NULL OR u.gradeLevel = :grade) AND " +
            "(:after IS NULL OR u.createdAt > :after) " +
            "ORDER BY u.createdAt ASC"
    )
    fun findForReview(
        @Param("reviewState") reviewState: String?,
        @Param("subject") subject: String?,
        @Param("grade") grade: String?,
        @Param("after") after: Instant?,
        pageable: Pageable,
    ): List<ContentUnitEntity>
}
