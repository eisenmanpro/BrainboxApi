package com.afrithecus.brainbox.api.attendance.repository

import com.afrithecus.brainbox.api.attendance.entity.AttendanceSmsDispatchEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface AttendanceSmsDispatchRepository : JpaRepository<AttendanceSmsDispatchEntity, UUID> {

    fun existsByAttendanceRecordIdAndRecipientUserId(recordId: UUID, recipientUserId: UUID): Boolean

    fun existsByPhoneNumberAndCreatedAtBetween(phoneNumber: String, from: Instant, to: Instant): Boolean

    fun findAllBySchoolIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        schoolId: UUID,
        from: Instant,
        to: Instant,
    ): List<AttendanceSmsDispatchEntity>

    fun countBySchoolIdAndCreatedAtBetween(schoolId: UUID, from: Instant, to: Instant): Long

    fun findAllByCreatedAtBetweenOrderByCreatedAtDesc(from: Instant, to: Instant): List<AttendanceSmsDispatchEntity>
}
