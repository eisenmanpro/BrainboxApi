package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.ReadableFileEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReadableFileRepository : JpaRepository<ReadableFileEntity, UUID> {

    fun findAllByIsActiveTrue(): List<ReadableFileEntity>

    fun findAllByCreatedByAndIsActiveTrueOrderByCreatedAtDesc(createdBy: UUID): List<ReadableFileEntity>

    fun findByClientId(clientId: String): ReadableFileEntity?
}
