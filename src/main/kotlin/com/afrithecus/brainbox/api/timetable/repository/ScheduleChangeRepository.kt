package com.afrithecus.brainbox.api.timetable.repository

import com.afrithecus.brainbox.api.timetable.entity.ScheduleChangeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ScheduleChangeRepository : JpaRepository<ScheduleChangeEntity, UUID> {

    fun findByClientId(clientId: String): ScheduleChangeEntity?

    fun findAllByTeacherIdOrderByRequestedAtDesc(teacherId: UUID): List<ScheduleChangeEntity>

    fun findAllBySchoolIdOrderByRequestedAtDesc(schoolId: UUID): List<ScheduleChangeEntity>
}
