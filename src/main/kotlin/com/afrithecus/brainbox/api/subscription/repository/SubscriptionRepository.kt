package com.afrithecus.brainbox.api.subscription.repository

import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SubscriptionRepository : JpaRepository<SubscriptionEntity, UUID> {

    fun findByUserId(userId: UUID): SubscriptionEntity?
}
