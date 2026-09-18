package com.afrithecus.brainbox.api.subscription.repository

import com.afrithecus.brainbox.api.subscription.entity.SubscriptionHistoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SubscriptionHistoryRepository : JpaRepository<SubscriptionHistoryEntity, UUID> {

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<SubscriptionHistoryEntity>
}
