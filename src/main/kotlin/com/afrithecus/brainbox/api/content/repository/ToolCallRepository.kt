package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ToolCallEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ToolCallRepository : JpaRepository<ToolCallEntity, UUID> {

    fun findAllByAgentRunId(agentRunId: UUID): List<ToolCallEntity>

    /**
     * Retention: removes every tool call whose run was created before [cutoff], so
     * the capture sweep is explicit and portable rather than depending on the
     * foreign-key cascade. Returns the count removed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(
        "DELETE FROM ToolCallEntity t WHERE t.agentRunId IN " +
            "(SELECT r.id FROM AgentRunEntity r WHERE r.createdAt < :cutoff)"
    )
    fun deleteByRunCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
