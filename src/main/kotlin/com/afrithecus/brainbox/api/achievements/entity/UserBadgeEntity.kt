package com.afrithecus.brainbox.api.achievements.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A badge earned by a user. */
@Entity
@Table(name = "user_badges")
class UserBadgeEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "badge_id", nullable = false)
    var badgeId: UUID = UUID.randomUUID()

    @Column(name = "earned_at", nullable = false, updatable = false)
    var earnedAt: Instant = Instant.now()
}
