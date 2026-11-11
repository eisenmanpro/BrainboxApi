package com.afrithecus.brainbox.api.attendance.repository

import com.afrithecus.brainbox.api.attendance.entity.AttendanceEscalationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AttendanceEscalationRepository : JpaRepository<AttendanceEscalationEntity, UUID> {

    fun existsByRecordId(recordId: UUID): Boolean
}
