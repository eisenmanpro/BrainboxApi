package com.afrithecus.brainbox.api.achievements.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Reward catalog entry (doc 03 §8.1). */
@Entity
@Table(name = "rewards")
class RewardEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 160)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var description: String = ""

    @Column(name = "xp_cost", nullable = false)
    var xpCost: Int = 0

    @Column(nullable = false, length = 64)
    var icon: String = ""

    @Column(nullable = false, length = 32)
    var type: String = "PREMIUM_ACCESS"

    @Column(nullable = false, length = 64)
    var category: String = ""

    @Column(nullable = false)
    var available: Boolean = true

    @Column(name = "valid_until")
    var validUntil: Instant? = null

    @Column(name = "image_url", length = 512)
    var imageUrl: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
