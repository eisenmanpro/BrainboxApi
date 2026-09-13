package com.afrithecus.brainbox.api.conference.repository

import com.afrithecus.brainbox.api.conference.entity.ConferenceSlotEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConferenceSlotRepository : JpaRepository<ConferenceSlotEntity, UUID> {

    fun findByClientId(clientId: String): ConferenceSlotEntity?

    fun findAllByTeacherIdOrderBySlotDateAsc(teacherId: UUID): List<ConferenceSlotEntity>

    fun findAllByTeacherIdInOrderBySlotDateAsc(teacherIds: Collection<UUID>): List<ConferenceSlotEntity>
}
