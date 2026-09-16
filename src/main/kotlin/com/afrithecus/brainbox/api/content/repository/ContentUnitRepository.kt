package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** One (subject, grade, leaf concept) row for the O1 coverage snapshot. */
interface CoverageLeafRow {
    val subject: String
    val gradeLevel: String
    val conceptId: UUID
}

/** One (subject, grade, task type) -> published count row for O1 coverage. */
interface UnitTaskCount {
    val subject: String
    val gradeLevel: String
    val taskType: String
    val total: Long
}

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

    /** O1 summary: the machine exception queue, i.e. units still UNREVIEWED created in [from, to). */
    fun countByReviewStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
        reviewState: String,
        from: Instant,
        to: Instant,
    ): Long

    /**
     * O1 coverage: every leaf topic (a concept with a parent and no children) with
     * its curriculum grade. A concept can map to more than one grade, so the pair
     * is the grouping key, not the concept alone.
     */
    @Query(
        "SELECT c.subject AS subject, m.gradeLevel AS gradeLevel, c.id AS conceptId " +
            "FROM ConceptEntity c JOIN CurriculumMapEntity m ON m.conceptId = c.id " +
            "WHERE c.parentId IS NOT NULL " +
            "AND NOT EXISTS (SELECT child FROM ConceptEntity child WHERE child.parentId = c.id)"
    )
    fun findCoverageLeafTopics(): List<CoverageLeafRow>

    /** O1 coverage: concepts that carry at least one published notes or quiz unit. */
    @Query(
        "SELECT DISTINCT u.conceptId FROM ContentUnitEntity u " +
            "WHERE u.reviewState = 'REVIEWED' AND u.taskType IN ('NOTES','QUIZ') AND u.conceptId IS NOT NULL"
    )
    fun findCoveredConceptIds(): List<UUID>

    /** O1 coverage: published units per (subject, grade, task type) for the requested task types. */
    @Query(
        "SELECT u.subject AS subject, u.gradeLevel AS gradeLevel, u.taskType AS taskType, COUNT(u) AS total " +
            "FROM ContentUnitEntity u WHERE u.reviewState = 'REVIEWED' AND u.taskType IN :taskTypes " +
            "GROUP BY u.subject, u.gradeLevel, u.taskType"
    )
    fun countPublishedBySubjectGradeTask(
        @Param("taskTypes") taskTypes: Collection<String>,
    ): List<UnitTaskCount>
}
