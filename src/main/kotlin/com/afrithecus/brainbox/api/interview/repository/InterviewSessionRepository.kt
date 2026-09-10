package com.afrithecus.brainbox.api.interview.repository

import com.afrithecus.brainbox.api.interview.entity.InterviewSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InterviewSessionRepository : JpaRepository<InterviewSessionEntity, UUID> {

    fun findAllByUserIdOrderByStartedAtDesc(userId: UUID): List<InterviewSessionEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): InterviewSessionEntity?
}
