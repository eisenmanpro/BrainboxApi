package com.afrithecus.brainbox.api.achievements.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** XP and streak-modifier state per user (doc 03 §7.1). */
@Entity
@Table(name = "user_achievements")
class UserAchievementsEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "total_xp", nullable = false)
    var totalXp: Int = 0

    @Column(name = "streak_freezes", nullable = false)
    var streakFreezes: Int = 0

    @Column(name = "grace_days", nullable = false)
    var graceDays: Int = 0
}
