package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LiveAttendanceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LiveAttendanceRepository : JpaRepository<LiveAttendanceEntity, UUID> {

    fun findByClassIdAndStudentId(classId: UUID, studentId: UUID): LiveAttendanceEntity?

    fun findAllByClassId(classId: UUID): List<LiveAttendanceEntity>

    fun findAllByStudentIdOrderByRecordedAtDesc(studentId: UUID): List<LiveAttendanceEntity>
}
