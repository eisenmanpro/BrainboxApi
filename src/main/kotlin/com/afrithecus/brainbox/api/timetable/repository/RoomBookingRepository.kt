package com.afrithecus.brainbox.api.timetable.repository

import com.afrithecus.brainbox.api.timetable.entity.RoomBookingEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface RoomBookingRepository : JpaRepository<RoomBookingEntity, UUID> {

    fun findByClientId(clientId: String): RoomBookingEntity?

    fun findAllBySchoolIdOrderByStartTimeAsc(schoolId: UUID): List<RoomBookingEntity>

    fun findAllByTeacherIdOrderByStartTimeAsc(teacherId: UUID): List<RoomBookingEntity>

    fun findAllByRoomIdOrderByStartTimeAsc(roomId: String): List<RoomBookingEntity>

    /** Overlapping bookings for a room: existing.start < newEnd and existing.end > newStart. */
    fun findAllByRoomIdAndStartTimeLessThanAndEndTimeGreaterThan(
        roomId: String,
        end: Instant,
        start: Instant,
    ): List<RoomBookingEntity>
}
