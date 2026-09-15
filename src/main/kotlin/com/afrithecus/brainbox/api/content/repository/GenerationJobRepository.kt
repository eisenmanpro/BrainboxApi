package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** One status -> count row for the admin queue depth summary. */
interface GenerationJobStatusCount {
    val status: String
    val total: Long
}

/** One (source, status) -> count row for the admin queue by-source summary. */
interface GenerationJobSourceStatusCount {
    val source: String
    val status: String
    val total: Long
}

/** One school -> count row for the H3 per-school daily budget usage. */
interface GenerationJobSchoolCount {
    val schoolId: UUID
    val total: Long
}

interface GenerationJobRepository : JpaRepository<GenerationJobEntity, UUID> {

    fun findAllByStatusOrderByCreatedAtAsc(status: String): List<GenerationJobEntity>

    /** H1 queue-depth gauge: number of jobs currently in [status]. */
    fun countByStatus(status: String): Long

    /** H2 admin summary: number of jobs per status. */
    @Query("SELECT j.status AS status, COUNT(j) AS total FROM GenerationJobEntity j GROUP BY j.status")
    fun countGroupedByStatus(): List<GenerationJobStatusCount>

    /** H2 admin summary: number of jobs per source and status. */
    @Query(
        "SELECT j.source AS source, j.status AS status, COUNT(j) AS total FROM GenerationJobEntity j " +
            "GROUP BY j.source, j.status"
    )
    fun countGroupedBySourceAndStatus(): List<GenerationJobSourceStatusCount>

    /** H3 platform budget usage: rows created at or after the start of the UTC day. */
    fun countByCreatedAtGreaterThanEqual(createdAt: Instant): Long

    /** H3 per-school budget usage: rows for [schoolId] created since the day start. */
    fun countBySchoolIdAndCreatedAtGreaterThanEqual(schoolId: UUID, createdAt: Instant): Long

    /** H3 admin visibility: jobs created since the day start grouped by school. */
    @Query(
        "SELECT j.schoolId AS schoolId, COUNT(j) AS total FROM GenerationJobEntity j " +
            "WHERE j.createdAt >= :since AND j.schoolId IS NOT NULL GROUP BY j.schoolId"
    )
    fun countGroupedBySchoolSince(@Param("since") since: Instant): List<GenerationJobSchoolCount>

    /** Router upsert lookup: the newest job row for a generation key. */
    fun findAllByGenerationKeyOrderByCreatedAtAsc(generationKey: String): List<GenerationJobEntity>

    /**
     * Phase 7.5a worker claim, extended by H2. The oldest QUEUED jobs whose retry
     * schedule has elapsed (a null next_attempt_at is immediately claimable) and
     * whose source is currently enabled. USER jobs are ordered ahead of BATCH, and
     * BATCH ahead of PROACTIVE, so an interactive request is never stuck behind a
     * seed batch; createdAt still breaks ties within a source.
     */
    @Query(
        "SELECT j FROM GenerationJobEntity j WHERE j.status = 'QUEUED' " +
            "AND (j.nextAttemptAt IS NULL OR j.nextAttemptAt <= :now) " +
            "AND j.source IN :sources " +
            "ORDER BY CASE WHEN j.source = 'USER' THEN 0 WHEN j.source = 'BATCH' THEN 1 ELSE 2 END, " +
            "j.createdAt ASC"
    )
    fun findClaimable(
        @Param("now") now: Instant,
        @Param("sources") sources: Collection<String>,
        pageable: Pageable,
    ): List<GenerationJobEntity>

    /** Phase 7.5a stale-run reclaim: RUNNING jobs untouched since the cutoff. */
    @Query("SELECT j FROM GenerationJobEntity j WHERE j.status = 'RUNNING' AND j.updatedAt <= :cutoff")
    fun findStaleRunning(@Param("cutoff") cutoff: Instant): List<GenerationJobEntity>
}
