package com.afrithecus.brainbox.api.career.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Reference mentor a student can request (doc 06 §1.5). */
@Entity
@Table(name = "mentors")
class MentorEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 128)
    var name: String = ""

    @Column(nullable = false, length = 160)
    var university: String = ""

    @Column(nullable = false, length = 160)
    var course: String = ""

    @Column(nullable = false)
    var rating: Double = 4.5

    @Column(name = "available_slots", nullable = false)
    var availableSlots: Int = 0

    @Column(name = "education_band_label", nullable = false, length = 120)
    var educationBandLabel: String = ""

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
