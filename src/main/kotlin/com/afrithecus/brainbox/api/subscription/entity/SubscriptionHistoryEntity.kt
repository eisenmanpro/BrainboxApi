package com.afrithecus.brainbox.api.subscription.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Append-only subscription history (doc 07 section 2.3). */
@Entity
@Table(name = "subscription_history")
class SubscriptionHistoryEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    /** CREATED, RENEWED or UPGRADED. */
    @Column(nullable = false, length = 16)
    var action: String = ""

    @Column(nullable = false, length = 16)
    var tier: String = ""

    @Column(nullable = false)
    var amount: Int = 0

    @Column(name = "transaction_id", length = 64)
    var transactionId: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @PrePersist
    fun touchOnPersist() {
        createdAt = Instant.now()
    }
}
