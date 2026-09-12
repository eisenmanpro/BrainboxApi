package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamRemediationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamRemediationRepository : JpaRepository<ExamRemediationEntity, UUID> {

    fun findAllByExamIdOrderByCbcStrandAsc(examId: UUID): List<ExamRemediationEntity>

    fun deleteAllByExamId(examId: UUID)
}
