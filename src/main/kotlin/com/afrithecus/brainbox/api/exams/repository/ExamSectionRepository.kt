package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamSectionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamSectionRepository : JpaRepository<ExamSectionEntity, UUID> {

    fun findAllByExamIdOrderBySortOrderAsc(examId: UUID): List<ExamSectionEntity>

    fun findByExamIdAndClientId(examId: UUID, clientId: String): ExamSectionEntity?
}
