package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** Projection for platform-wide subject popularity. */
interface ExamSubmissionCountByExam {
    fun getExamId(): UUID
    fun getTotal(): Long
}

interface ExamSubmissionRepository : JpaRepository<ExamSubmissionEntity, UUID> {

    @Query("SELECT s.examId AS examId, COUNT(s) AS total FROM ExamSubmissionEntity s GROUP BY s.examId")
    fun countsByExam(): List<ExamSubmissionCountByExam>

    fun findByUserIdAndExamId(userId: UUID, examId: UUID): ExamSubmissionEntity?

    fun findAllByUserId(userId: UUID): List<ExamSubmissionEntity>

    fun findAllByExamId(examId: UUID): List<ExamSubmissionEntity>

    fun findAllByUserIdIn(userIds: Collection<UUID>): List<ExamSubmissionEntity>

    fun findAllByExamIdIn(examIds: Collection<UUID>): List<ExamSubmissionEntity>
}
