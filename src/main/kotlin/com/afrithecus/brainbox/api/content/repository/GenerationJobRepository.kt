package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface GenerationJobRepository : JpaRepository<GenerationJobEntity, UUID> {

    fun findAllByStatusOrderByCreatedAtAsc(status: String): List<GenerationJobEntity>

    /** H1 queue-depth gauge: number of jobs currently in [status]. */
    fun countByStatus(status: String): Long

    /** Router upsert lookup: the newest job row for a generation key. */
    fun findAllByGenerationKeyOrderByCreatedAtAsc(generationKey: String): List<GenerationJobEntity>

    /**
     * Phase 7.5a worker claim: the oldest QUEUED jobs whose retry schedule has
     * elapsed (a null next_attempt_at is immediately claimable).
     */
    @Query(
        "SELECT j FROM GenerationJobEntity j WHERE j.status = 'QUEUED' " +
            "AND (j.nextAttemptAt IS NULL OR j.nextAttemptAt <= :now) " +
            "ORDER BY j.createdAt ASC"
    )
    fun findClaimable(@Param("now") now: Instant, pageable: Pageable): List<GenerationJobEntity>

    /** Phase 7.5a stale-run reclaim: RUNNING jobs untouched since the cutoff. */
    @Query("SELECT j FROM GenerationJobEntity j WHERE j.status = 'RUNNING' AND j.updatedAt <= :cutoff")
    fun findStaleRunning(@Param("cutoff") cutoff: Instant): List<GenerationJobEntity>
}
