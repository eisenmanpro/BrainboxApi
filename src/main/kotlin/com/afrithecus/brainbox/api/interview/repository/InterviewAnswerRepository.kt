package com.afrithecus.brainbox.api.interview.repository

import com.afrithecus.brainbox.api.interview.entity.InterviewAnswerEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InterviewAnswerRepository : JpaRepository<InterviewAnswerEntity, UUID> {

    fun findBySessionIdAndQuestionId(sessionId: UUID, questionId: UUID): InterviewAnswerEntity?

    fun findAllBySessionIdOrderByCreatedAtAsc(sessionId: UUID): List<InterviewAnswerEntity>
}
