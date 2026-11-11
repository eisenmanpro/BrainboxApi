package com.afrithecus.brainbox.api.attendance.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** One scheduled re-notification for an absence (F8-A2), one row per attendance record. */
@Entity
@Table(name = "attendance_escalations")
class AttendanceEscalationEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "record_id", nullable = false, updatable = false)
    var recordId: UUID = UUID.randomUUID()

    @Column(name = "notified_at", nullable = false)
    var notifiedAt: Instant = Instant.now()
}
