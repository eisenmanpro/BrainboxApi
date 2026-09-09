package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamQuestionRepository : JpaRepository<ExamQuestionEntity, UUID> {

    fun findAllByExamIdOrderByOrderIndexAsc(examId: UUID): List<ExamQuestionEntity>

    fun countByExamId(examId: UUID): Long
}
