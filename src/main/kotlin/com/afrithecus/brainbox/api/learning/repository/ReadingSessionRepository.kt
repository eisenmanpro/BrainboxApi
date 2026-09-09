package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.ReadingSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReadingSessionRepository : JpaRepository<ReadingSessionEntity, UUID> {

    fun findAllByUserIdAndFileIdOrderByStartTimeDesc(userId: UUID, fileId: UUID): List<ReadingSessionEntity>
}
