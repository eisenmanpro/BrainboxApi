package com.afrithecus.brainbox.api.timetable.repository

import com.afrithecus.brainbox.api.timetable.entity.TimetableEntryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TimetableEntryRepository : JpaRepository<TimetableEntryEntity, UUID> {

    fun findByClientId(clientId: String): TimetableEntryEntity?

    fun findAllByTeacherIdOrderByDayOfWeekAscStartTimeAsc(teacherId: UUID): List<TimetableEntryEntity>

    fun findAllByTeacherIdAndDayOfWeekOrderByStartTimeAsc(teacherId: UUID, dayOfWeek: Int): List<TimetableEntryEntity>

    fun findAllByClassIdIn(classIds: Collection<String>): List<TimetableEntryEntity>
}
