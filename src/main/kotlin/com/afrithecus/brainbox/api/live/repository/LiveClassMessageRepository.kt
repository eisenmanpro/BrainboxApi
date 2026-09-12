package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LiveClassMessageEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LiveClassMessageRepository : JpaRepository<LiveClassMessageEntity, UUID> {

    fun findByClientId(clientId: String): LiveClassMessageEntity?

    fun findAllByClassIdOrderBySentAtAsc(classId: UUID): List<LiveClassMessageEntity>

    fun countByClassId(classId: UUID): Long
}
