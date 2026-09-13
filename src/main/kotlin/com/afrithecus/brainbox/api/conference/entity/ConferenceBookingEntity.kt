package com.afrithecus.brainbox.api.conference.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A parent's booking of a conference slot (doc 04 section 15.2). */
@Entity
@Table(name = "conference_bookings")
class ConferenceBookingEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "slot_id", nullable = false)
    var slotId: UUID = UUID.randomUUID()

    @Column(name = "parent_id", nullable = false)
    var parentId: UUID = UUID.randomUUID()

    @Column(name = "child_id", nullable = false)
    var childId: UUID = UUID.randomUUID()

    @Column(name = "teacher_name", length = 160)
    var teacherName: String? = null

    @Column(name = "booking_date", nullable = false)
    var bookingDate: Instant = Instant.now()

    @Column(name = "booking_time", length = 8)
    var bookingTime: String? = null

    @Column(name = "meet_link", length = 512)
    var meetLink: String? = null

    @Column(columnDefinition = "text")
    var notes: String? = null

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    /** When the parent requested the seat; drives the confirmation expiry window. */
    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant = Instant.now()

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null

    @Column(name = "confirmed_by")
    var confirmedBy: UUID? = null

    @Column(name = "reminder_sent_at")
    var reminderSentAt: Instant? = null
}
