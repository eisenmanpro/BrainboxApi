package com.afrithecus.brainbox.api.conference.repository

import com.afrithecus.brainbox.api.conference.entity.ConferenceBookingEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConferenceBookingRepository : JpaRepository<ConferenceBookingEntity, UUID> {

    fun findByClientId(clientId: String): ConferenceBookingEntity?

    fun findAllBySlotIdOrderByCreatedAtAsc(slotId: UUID): List<ConferenceBookingEntity>

    fun findAllBySlotIdAndStatusOrderByCreatedAtAsc(slotId: UUID, status: String): List<ConferenceBookingEntity>

    fun findAllByParentIdOrderByBookingDateDesc(parentId: UUID): List<ConferenceBookingEntity>

    fun findAllByChildIdOrderByBookingDateDesc(childId: UUID): List<ConferenceBookingEntity>

    fun findBySlotIdAndChildIdAndStatus(slotId: UUID, childId: UUID, status: String): ConferenceBookingEntity?

    fun countBySlotIdAndStatus(slotId: UUID, status: String): Long

    fun findAllByStatusOrderByRequestedAtAsc(status: String): List<ConferenceBookingEntity>
}
