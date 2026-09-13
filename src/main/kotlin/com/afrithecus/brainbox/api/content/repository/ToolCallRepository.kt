package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ToolCallEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ToolCallRepository : JpaRepository<ToolCallEntity, UUID> {

    fun findAllByAgentRunId(agentRunId: UUID): List<ToolCallEntity>
}
