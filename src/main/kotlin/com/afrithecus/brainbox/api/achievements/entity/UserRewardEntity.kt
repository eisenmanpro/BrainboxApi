package com.afrithecus.brainbox.api.achievements.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A redeemed reward plus its generated coupon code. */
@Entity
@Table(name = "user_rewards")
class UserRewardEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "reward_id", nullable = false)
    var rewardId: UUID = UUID.randomUUID()

    @Column(name = "coupon_code", nullable = false, length = 32)
    var couponCode: String = ""

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    var redeemedAt: Instant = Instant.now()
}
