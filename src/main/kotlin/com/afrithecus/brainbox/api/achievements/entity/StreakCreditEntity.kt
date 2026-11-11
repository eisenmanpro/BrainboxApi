package com.afrithecus.brainbox.api.achievements.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One missed local day covered by a purchased streak freeze or grace day (F5-A3). */
@Entity
@Table(name = "streak_credits")
class StreakCreditEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "credit_date", nullable = false)
    var creditDate: LocalDate = LocalDate.now()

    /** FREEZE or GRACE. */
    @Column(nullable = false, length = 16)
    var kind: String = "FREEZE"

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
