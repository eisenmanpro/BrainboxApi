package com.afrithecus.brainbox.api.interview.repository

import com.afrithecus.brainbox.api.interview.entity.InterviewSessionQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InterviewSessionQuestionRepository : JpaRepository<InterviewSessionQuestionEntity, UUID> {

    fun findAllBySessionIdOrderByOrderIndexAsc(sessionId: UUID): List<InterviewSessionQuestionEntity>
}
