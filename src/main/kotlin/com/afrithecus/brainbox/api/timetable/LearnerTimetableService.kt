package com.afrithecus.brainbox.api.timetable

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.repository.TimetableEntryRepository
import com.afrithecus.brainbox.api.timetable.web.LearnerTimetableSlotPayload
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * The signed-in learner's own weekly classes (docs/ongoing/api_timetable_changes.md).
 * Slots are the teacher timetable entries of the classes the learner is enrolled
 * in, materialised into the current school week so the unified calendar can render
 * them without recomputing days.
 */
@Service
class LearnerTimetableService(
    private val entryRepository: TimetableEntryRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZoneId: String,
) {

    @Transactional(readOnly = true)
    fun timetable(student: UserEntity): List<LearnerTimetableSlotPayload> {
        val classIds = membershipRepository.findAllByStudentId(student.id)
            .map { it.classId.toString() }
            .toSet()
        if (classIds.isEmpty()) return emptyList()
        val entries = entryRepository.findAllByClassIdIn(classIds)
        if (entries.isEmpty()) return emptyList()
        val zone = ZoneId.of(schoolZoneId)
        val teacherNames = userRepository.findAllById(entries.map { it.teacherId }.toSet())
            .associate { it.id to it.name }
        val monday = LocalDate.now(clock.withZone(zone))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        return entries.mapNotNull { entry ->
            val start = parseTime(entry.startTime) ?: return@mapNotNull null
            val end = parseTime(entry.endTime) ?: return@mapNotNull null
            val day = entry.dayOfWeek.coerceIn(1, 7)
            val date = monday.plusDays((day - 1).toLong())
            LearnerTimetableSlotPayload(
                id = entry.clientId,
                classId = entry.classId ?: "",
                subject = entry.subject,
                teacherName = teacherNames[entry.teacherId] ?: "",
                room = entry.roomName ?: entry.roomId,
                dayOfWeek = day,
                startMillis = ZonedDateTime.of(date, start, zone).toInstant().toEpochMilli(),
                endMillis = ZonedDateTime.of(date, end, zone).toInstant().toEpochMilli(),
            )
        }.sortedWith(compareBy({ it.dayOfWeek }, { it.startMillis }))
    }

    private fun parseTime(raw: String): LocalTime? =
        runCatching { LocalTime.parse(raw.trim(), TIME_FORMAT) }.getOrNull()

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
