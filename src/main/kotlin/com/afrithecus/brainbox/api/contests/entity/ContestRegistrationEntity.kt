package com.afrithecus.brainbox.api.contests.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Student registration for a contest (doc 05 §1.4). */
@Entity
@Table(name = "contest_registrations")
class ContestRegistrationEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "contest_id", nullable = false)
    var contestId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "registered_at", nullable = false, updatable = false)
    var registeredAt: Instant = Instant.now()
}
