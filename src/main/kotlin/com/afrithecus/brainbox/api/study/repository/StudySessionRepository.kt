package com.afrithecus.brainbox.api.study.repository

import com.afrithecus.brainbox.api.study.entity.StudySessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface StudySessionRepository : JpaRepository<StudySessionEntity, UUID> {

    fun findAllByUserIdOrderByStartTimeDesc(userId: UUID): List<StudySessionEntity>

    fun findByUserIdAndSubjectAndTopicAndStartTime(
        userId: UUID,
        subject: String,
        topic: String,
        startTime: Instant,
    ): StudySessionEntity?
}
