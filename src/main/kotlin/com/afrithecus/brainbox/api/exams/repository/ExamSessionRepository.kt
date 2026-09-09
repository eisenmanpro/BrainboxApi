package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamSessionEntity
import com.afrithecus.brainbox.api.exams.model.SessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamSessionRepository : JpaRepository<ExamSessionEntity, UUID> {

    fun findByUserIdAndExamId(userId: UUID, examId: UUID): ExamSessionEntity?

    fun findAllByUserId(userId: UUID): List<ExamSessionEntity>

    fun countByUserIdAndStatus(userId: UUID, status: SessionStatus): Long
}
