package com.afrithecus.brainbox.api.subscription.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One authoritative subscription row per user (doc 01 §4).
 * Expiry is computed server-side from [expiryDate]; the client only mirrors it.
 */
@Entity
@Table(name = "subscriptions")
class SubscriptionEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: SubscriptionStatus = SubscriptionStatus.NONE

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var tier: SubscriptionTier = SubscriptionTier.BASE

    @Column(name = "expiry_date")
    var expiryDate: Instant? = null

    @Column(name = "total_paid", nullable = false)
    var totalPaid: Int = 0
}
