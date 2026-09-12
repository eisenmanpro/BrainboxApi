package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LiveClassParticipantEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LiveClassParticipantRepository : JpaRepository<LiveClassParticipantEntity, UUID> {

    fun findByClassIdAndUserId(classId: UUID, userId: UUID): LiveClassParticipantEntity?

    fun findAllByClassIdAndIsRemovedFalseOrderByJoinTimeAsc(classId: UUID): List<LiveClassParticipantEntity>

    fun countByClassIdAndIsRemovedFalse(classId: UUID): Long
}
