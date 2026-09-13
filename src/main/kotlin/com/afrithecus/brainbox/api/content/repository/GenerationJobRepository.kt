package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GenerationJobRepository : JpaRepository<GenerationJobEntity, UUID> {

    fun findAllByStatusOrderByCreatedAtAsc(status: String): List<GenerationJobEntity>

    /** Router upsert lookup: the newest job row for a generation key. */
    fun findAllByGenerationKeyOrderByCreatedAtAsc(generationKey: String): List<GenerationJobEntity>
}