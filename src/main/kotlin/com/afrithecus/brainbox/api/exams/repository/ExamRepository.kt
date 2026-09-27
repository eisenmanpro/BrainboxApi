package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamRepository : JpaRepository<ExamEntity, UUID> {

    fun findAllByStatus(status: ExamStatus): List<ExamEntity>

    fun findAllByStatusAndSubjectIgnoreCase(status: ExamStatus, subject: String): List<ExamEntity>

    fun findByClientId(clientId: String): ExamEntity?

    fun findAllByCreatedByOrderByCreatedAtDesc(createdBy: UUID): List<ExamEntity>

    /** Personal practice papers belong to exactly one learner (§B7). */
    fun findAllByOwnerUserIdAndScopeOrderByCreatedAtDesc(
        ownerUserId: UUID,
        scope: ExamScope,
    ): List<ExamEntity>
}
