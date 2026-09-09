package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamSubmissionRepository : JpaRepository<ExamSubmissionEntity, UUID> {

    fun findByUserIdAndExamId(userId: UUID, examId: UUID): ExamSubmissionEntity?

    fun findAllByUserId(userId: UUID): List<ExamSubmissionEntity>

    fun findAllByExamId(examId: UUID): List<ExamSubmissionEntity>
}
