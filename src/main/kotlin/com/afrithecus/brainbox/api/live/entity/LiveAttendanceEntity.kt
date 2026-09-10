package com.afrithecus.brainbox.api.live.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Attendance for one student in one live class. */
@Entity
@Table(name = "live_attendance")
class LiveAttendanceEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "class_id", nullable = false)
    var classId: UUID = UUID.randomUUID()

    @Column(name = "student_id", nullable = false)
    var studentId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 16)
    var status: String = "PRESENT"

    @Column(name = "joined_at")
    var joinedAt: Instant? = null

    @Column(name = "left_at")
    var leftAt: Instant? = null

    @Column(name = "duration_minutes", nullable = false)
    var durationMinutes: Int = 0

    @Column(name = "is_present", nullable = false)
    var isPresent: Boolean = true

    @Column(length = 255)
    var reason: String? = null

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now()
}
