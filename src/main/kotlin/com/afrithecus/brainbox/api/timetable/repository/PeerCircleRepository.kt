package com.afrithecus.brainbox.api.timetable.repository

import com.afrithecus.brainbox.api.timetable.entity.PeerCircleEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PeerCircleRepository : JpaRepository<PeerCircleEntity, UUID> {

    fun findByClientId(clientId: String): PeerCircleEntity?

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<PeerCircleEntity>
}
