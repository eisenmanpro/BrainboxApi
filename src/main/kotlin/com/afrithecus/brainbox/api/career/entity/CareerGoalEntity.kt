package com.afrithecus.brainbox.api.career.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A saved career goal / plan for a student (doc 06 §1.4). */
@Entity
@Table(name = "career_goals")
class CareerGoalEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    /** CareerGoal enum name. */
    @Column(nullable = false, length = 64)
    var goal: String = "GENERAL"

    @Column(name = "target_date")
    var targetDate: Instant? = null

    /** JSON array string of milestone descriptions. */
    @Column(columnDefinition = "text")
    var milestones: String? = null

    @Column(nullable = false, length = 16)
    var status: String = "ACTIVE"
}
