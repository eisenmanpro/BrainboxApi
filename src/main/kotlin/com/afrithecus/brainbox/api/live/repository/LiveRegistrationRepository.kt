package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LiveRegistrationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LiveRegistrationRepository : JpaRepository<LiveRegistrationEntity, UUID> {

    fun findByClassIdAndStudentId(classId: UUID, studentId: UUID): LiveRegistrationEntity?

    fun findAllByClassId(classId: UUID): List<LiveRegistrationEntity>

    fun countByClassId(classId: UUID): Long
}
