package com.afrithecus.brainbox.api.live.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A student's registration for a live class. */
@Entity
@Table(name = "live_registrations")
class LiveRegistrationEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(name = "registered_at", nullable = false, updatable = false)
    var registeredAt: Instant = Instant.now()
}
