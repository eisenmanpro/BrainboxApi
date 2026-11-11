package com.afrithecus.brainbox.api.attendance.repository

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface AttendanceRecordRepository : JpaRepository<AttendanceRecordEntity, UUID> {

    fun findAllByClassIdAndAttendanceDate(classId: UUID, date: LocalDate): List<AttendanceRecordEntity>

    fun findAllByAttendanceDateAndStatus(date: LocalDate, status: AttendanceStatus): List<AttendanceRecordEntity>

    fun findByClassIdAndAttendanceDateAndStudentId(classId: UUID, date: LocalDate, studentId: UUID): AttendanceRecordEntity?

    fun findAllByStudentIdOrderByAttendanceDateDesc(studentId: UUID): List<AttendanceRecordEntity>

    fun findAllByClassIdAndAttendanceDateBetween(classId: UUID, start: LocalDate, end: LocalDate): List<AttendanceRecordEntity>

    fun findAllByStudentIdInAndAttendanceDateBetween(studentIds: Collection<UUID>, start: LocalDate, end: LocalDate): List<AttendanceRecordEntity>
}
