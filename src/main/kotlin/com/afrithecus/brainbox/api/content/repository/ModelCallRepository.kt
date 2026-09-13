package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ModelCallRepository : JpaRepository<ModelCallEntity, UUID> {

    fun findAllByAgentRunId(agentRunId: UUID): List<ModelCallEntity>
}
