package com.afrithecus.brainbox.api.achievements.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One XP award, retained so weekly leaderboards are server-computed. */
@Entity
@Table(name = "xp_events")
class XpEventEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var amount: Int = 0

    @Column(name = "activity_type", nullable = false, length = 64)
    var activityType: String = ""

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
