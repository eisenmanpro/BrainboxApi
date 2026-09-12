package com.afrithecus.brainbox.api.timetable.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A teacher's request to move or cover a scheduled class, reviewed by a
 * coordinator (doc 04 section 9.5).
 */
@Entity
@Table(name = "teacher_schedule_changes")
class ScheduleChangeEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "teacher_name", length = 160)
    var teacherName: String? = null

    @Column(name = "day_label", nullable = false, length = 32)
    var day: String = ""

    @Column(name = "class_name", nullable = false)
    var className: String = ""

    @Column(nullable = false, length = 128)
    var subject: String = ""

    @Column(name = "start_time", nullable = false, length = 8)
    var startTime: String = ""

    @Column(name = "end_time", nullable = false, length = 8)
    var endTime: String = ""

    @Column(columnDefinition = "text")
    var reason: String? = null

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant = Instant.now()

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decided_by")
    var decidedBy: UUID? = null

    @Column(name = "decision_note", columnDefinition = "text")
    var decisionNote: String? = null
}
