package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentRunRepository : JpaRepository<AgentRunEntity, UUID> {

    fun findAllByGenerationKeyOrderByCreatedAtDesc(generationKey: String): List<AgentRunEntity>
}
