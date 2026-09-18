package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** One provider's call/token/cost/latency totals for the O1 rollup window. */
interface ModelCallProviderAggregate {
    val provider: String
    val callCount: Long
    val promptTokens: Long
    val completionTokens: Long
    val costMicros: Long
    val latencySum: Long
}

/** One failed provider call's coarse fields, classified in memory by the rollup. */
interface ModelCallFailure {
    val provider: String
    val error: String?
}

interface ModelCallRepository : JpaRepository<ModelCallEntity, UUID> {

    fun findAllByAgentRunId(agentRunId: UUID): List<ModelCallEntity>

    /**
     * O1 rollup: per-provider call count, tokens, cost and latency sum for
     * [from, to). The average latency is derived as latencySum / callCount so the
     * same query is exact on H2 and PostgreSQL (no numeric/double AVG mismatch).
     */
    @Query(
        "SELECT m.provider AS provider, COUNT(m) AS callCount, " +
            "SUM(m.promptTokens) AS promptTokens, SUM(m.completionTokens) AS completionTokens, " +
            "SUM(m.costMicros) AS costMicros, SUM(m.latencyMs) AS latencySum " +
            "FROM ModelCallEntity m WHERE m.createdAt >= :from AND m.createdAt < :to " +
            "GROUP BY m.provider"
    )
    fun aggregateByProviderInWindow(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<ModelCallProviderAggregate>

    /** H3 spend budget: total provider cost in micros recorded since [since]; null when none. */
    @Query("SELECT SUM(m.costMicros) FROM ModelCallEntity m WHERE m.createdAt >= :since")
    fun sumCostSince(@Param("since") since: Instant): Long?

    /**
     * H3 per-school spend: total provider cost for runs attributed to [schoolId]
     * since [since]. The subquery reads agent_runs.school_id, set from the job (and
     * from the unit for verification/critique), so platform-scope work is excluded.
     */
    @Query(
        "SELECT SUM(m.costMicros) FROM ModelCallEntity m WHERE m.createdAt >= :since " +
            "AND m.agentRunId IN (SELECT r.id FROM AgentRunEntity r WHERE r.schoolId = :schoolId)"
    )
    fun sumCostForSchoolSince(@Param("schoolId") schoolId: UUID, @Param("since") since: Instant): Long?

    /**
     * Retention: removes every call whose run was created before [cutoff]. Explicit
     * rather than relying on the foreign-key cascade so the sweep is portable and
     * the child rows are gone before the parent runs are removed. Returns the count.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "DELETE FROM ModelCallEntity m WHERE m.agentRunId IN " +
            "(SELECT r.id FROM AgentRunEntity r WHERE r.createdAt < :cutoff)"
    )
    fun deleteByRunCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int

    /** O1 rollup: failed provider calls in [from, to), classified into reasons in memory. */
    @Query(
        "SELECT m.provider AS provider, m.error AS error FROM ModelCallEntity m " +
            "WHERE m.success = false AND m.createdAt >= :from AND m.createdAt < :to"
    )
    fun findFailuresInWindow(
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<ModelCallFailure>
}
