package com.afrithecus.brainbox.api.conference.repository

import com.afrithecus.brainbox.api.conference.entity.ConferenceDiscussionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Slot-scoped discussion reads/writes. Every lookup carries the slot id, so a
 * client id repeated in another slot can never resolve to the wrong thread.
 */
interface ConferenceDiscussionRepository : JpaRepository<ConferenceDiscussionEntity, UUID> {

    fun findBySlotIdAndClientId(slotId: UUID, clientId: String): ConferenceDiscussionEntity?

    fun findBySlotIdAndId(slotId: UUID, id: UUID): ConferenceDiscussionEntity?

    fun findAllBySlotIdOrderByCreatedAtAscIdAsc(slotId: UUID): List<ConferenceDiscussionEntity>
}
