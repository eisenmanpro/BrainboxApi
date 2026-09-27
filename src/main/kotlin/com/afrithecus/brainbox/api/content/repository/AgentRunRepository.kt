package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface AgentRunRepository : JpaRepository<AgentRunEntity, UUID> {

    fun findAllByGenerationKeyOrderByCreatedAtDesc(generationKey: String): List<AgentRunEntity>

    /**
     * Run counts per domain subject agent, for the console's view over the capture
     * tables. Runs that predate agent attribution are grouped under their null code and
     * reported separately.
     */
    @Query("SELECT r.agentCode AS agentCode, COUNT(r) AS total FROM AgentRunEntity r GROUP BY r.agentCode")
    fun countsByAgent(): List<AgentRunCount>

    /** Projection for [countsByAgent]. */
    interface AgentRunCount {
        fun getAgentCode(): String?
        fun getTotal(): Long
    }

    /**
     * Retention: removes capture runs created before [cutoff]. The sweep removes
     * model_calls and tool_calls first (see their repositories), so this is the last
     * step that bounds the whole capture footprint. Returns the number of runs removed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}
