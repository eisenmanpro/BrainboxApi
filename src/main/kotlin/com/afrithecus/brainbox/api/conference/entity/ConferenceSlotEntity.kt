package com.afrithecus.brainbox.api.conference.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher's conference slot (doc 04 section 15.1). */
@Entity
@Table(name = "conference_slots")
class ConferenceSlotEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "teacher_name", length = 160)
    var teacherName: String? = null

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(name = "slot_date", nullable = false)
    var slotDate: Instant = Instant.now()

    @Column(name = "start_time", nullable = false, length = 8)
    var startTime: String = ""

    @Column(name = "end_time", nullable = false, length = 8)
    var endTime: String = ""

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 30

    @Column(name = "max_bookings", nullable = false)
    var maxBookings: Int = 1

    @Column(name = "meet_link", length = 512)
    var meetLink: String? = null

    @Column(name = "is_virtual", nullable = false)
    var isVirtual: Boolean = true

    @Column(length = 255)
    var location: String? = null

    @Column(name = "is_recurring", nullable = false)
    var isRecurring: Boolean = false

    @Column(name = "recurrence_rule", length = 255)
    var recurrenceRule: String? = null

    @Column(nullable = false, length = 16)
    var status: String = "OPEN"

    @Column(name = "cancellation_reason", length = 255)
    var cancellationReason: String? = null

    @Column(name = "audience_target", nullable = false, length = 32)
    var audienceTarget: String = "WHOLE_SCHOOL"

    @Column(name = "created_by_role", nullable = false, length = 16)
    var createdByRole: String = "TEACHER"

    @Column(name = "linked_live_class_id", length = 80)
    var linkedLiveClassId: String? = null
}
