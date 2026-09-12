package com.afrithecus.brainbox.api.timetable.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A teacher's reservation of a room for an absolute time window (doc 04 section 9.2). */
@Entity
@Table(name = "teacher_room_bookings")
class RoomBookingEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "teacher_id", nullable = false)
    var teacherId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "room_id", nullable = false, length = 80)
    var roomId: String = ""

    @Column(name = "room_name", nullable = false)
    var roomName: String = ""

    @Column(name = "teacher_name", length = 160)
    var teacherName: String? = null

    @Column(name = "start_time", nullable = false)
    var startTime: Instant = Instant.now()

    @Column(name = "end_time", nullable = false)
    var endTime: Instant = Instant.now()

    @Column(length = 255)
    var purpose: String? = null
}
