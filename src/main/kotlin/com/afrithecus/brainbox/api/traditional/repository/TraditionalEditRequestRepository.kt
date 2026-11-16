package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalEditRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalEditRequestRepository : JpaRepository<TraditionalEditRequestEntity, UUID> {

    fun findAllByExamIdOrderByCreatedAtDesc(examId: UUID): List<TraditionalEditRequestEntity>

    fun findAllByExamIdAndStatus(examId: UUID, status: com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus): List<TraditionalEditRequestEntity>

    /** Every request across a set of exams (the caller's scoped queue), newest first. */
    fun findAllByExamIdInOrderByCreatedAtDesc(examIds: Collection<UUID>): List<TraditionalEditRequestEntity>
}
