package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamReviewMarkEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamReviewMarkRepository : JpaRepository<ExamReviewMarkEntity, UUID> {

    fun findBySubmissionIdAndQuestionId(submissionId: UUID, questionId: UUID): ExamReviewMarkEntity?

    fun findAllBySubmissionIdIn(submissionIds: Collection<UUID>): List<ExamReviewMarkEntity>

    fun findAllByQuestionIdIn(questionIds: Collection<UUID>): List<ExamReviewMarkEntity>
}
