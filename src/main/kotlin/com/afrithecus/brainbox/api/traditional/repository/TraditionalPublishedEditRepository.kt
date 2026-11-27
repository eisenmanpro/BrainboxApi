package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalPublishedEditEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalPublishedEditRepository : JpaRepository<TraditionalPublishedEditEntity, UUID> {
    fun findAllByExamId(examId: UUID): List<TraditionalPublishedEditEntity>
}
