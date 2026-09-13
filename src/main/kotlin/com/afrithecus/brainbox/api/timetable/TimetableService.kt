package com.afrithecus.brainbox.api.timetable

import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.timetable.entity.RoomBookingEntity
import com.afrithecus.brainbox.api.timetable.entity.TimetableEntryEntity
import com.afrithecus.brainbox.api.timetable.repository.RoomBookingRepository
import com.afrithecus.brainbox.api.timetable.repository.TimetableEntryRepository
import com.afrithecus.brainbox.api.timetable.web.RoomBookingPayload
import com.afrithecus.brainbox.api.timetable.web.TimetableEntryPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Teacher timetable and room bookings (doc 04 section 9.1/9.2). Every write is
 * self-scoped and idempotent on the client-supplied id because the app writes
 * Room first and replays queued mutations after a failure.
 */
@Service
class TimetableService(
    private val entryRepository: TimetableEntryRepository,
    private val bookingRepository: RoomBookingRepository,
    private val classRepository: TeacherClassRepository,
) {

    @Transactional(readOnly = true)
    fun ownEntries(teacher: UserEntity): List<TimetableEntryPayload> {
        requireTeacher(teacher)
        return entriesFor(teacher).map(::toEntryPayload)
    }

    /** KENEC/physical export view; the client renders the same entries it holds. */
    @Transactional(readOnly = true)
    fun kenyanTimetable(teacher: UserEntity): List<TimetableEntryPayload> {
        requireTeacher(teacher)
        return entriesFor(teacher).map(::toEntryPayload)
    }

    /**
     * Builds a Kenyan-style practical day from the teacher's classes, upserting
     * on deterministic ids so repeated calls never duplicate slots.
     */
    @Transactional
    fun autoSchedule(teacher: UserEntity): List<TimetableEntryPayload> {
        requireTeacher(teacher)
        val classes = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
        var cursor = 8 * 60
        classes.forEachIndexed { index, clazz ->
            val practical = practicalFor(clazz.subject)
            val duration = if (practical.type == "NONE") 60 else 120
            val clientId = "auto_" + clazz.id + "_" + (index + 1)
            val entity = entryRepository.findByClientId(clientId)
                ?: TimetableEntryEntity().apply {
                    this.clientId = clientId
                    teacherId = teacher.id
                }
            entity.teacherId = teacher.id
            entity.schoolId = teacher.schoolId
            entity.classId = clazz.id.toString()
            entity.className = clazz.name
            entity.subject = clazz.subject
            entity.dayOfWeek = MONDAY
            entity.startTime = formatMinutes(cursor)
            entity.endTime = formatMinutes(cursor + duration)
            entity.entryType = practical.entryType
            entity.practicalBlockType = practical.type
            entryRepository.save(entity)
            cursor += duration + 10
        }
        entryRepository.flush()
        return entriesFor(teacher).map(::toEntryPayload)
    }

    @Transactional
    fun createEntry(teacher: UserEntity, request: TimetableEntryPayload): TimetableEntryPayload {
        requireTeacher(teacher)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "entry_" + UUID.randomUUID()
        val existing = entryRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your timetable entry")
        }
        val entity = existing ?: TimetableEntryEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        applyEntry(entity, request, teacher)
        rejectEntryClash(entity)
        entryRepository.saveAndFlush(entity)
        return toEntryPayload(entity)
    }

    @Transactional
    fun updateEntry(teacher: UserEntity, entryId: String, request: TimetableEntryPayload): TimetableEntryPayload {
        requireTeacher(teacher)
        val entity = resolveEntry(teacher, entryId) ?: throw notFound("Timetable entry not found")
        applyEntry(entity, request, teacher)
        rejectEntryClash(entity)
        entryRepository.saveAndFlush(entity)
        return toEntryPayload(entity)
    }

    /** Repeat-safe: an unknown entry is treated as already deleted. */
    @Transactional
    fun deleteEntry(teacher: UserEntity, entryId: String) {
        requireTeacher(teacher)
        val entity = resolveEntry(teacher, entryId) ?: return
        entryRepository.delete(entity)
    }

    /** Room bookings are visible school-wide so teachers can avoid clashes. */
    @Transactional(readOnly = true)
    fun bookings(teacher: UserEntity, roomId: String?): List<RoomBookingPayload> {
        requireTeacher(teacher)
        val all = if (teacher.schoolId != null) {
            bookingRepository.findAllBySchoolIdOrderByStartTimeAsc(teacher.schoolId!!)
        } else {
            bookingRepository.findAllByTeacherIdOrderByStartTimeAsc(teacher.id)
        }
        return all
            .filter { roomId.isNullOrBlank() || it.roomId == roomId }
            .map(::toBookingPayload)
    }

    @Transactional
    fun bookRoom(teacher: UserEntity, request: RoomBookingPayload): RoomBookingPayload {
        requireTeacher(teacher)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "booking_" + UUID.randomUUID()
        val existing = bookingRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your room booking")
        }
        val roomId = request.roomId.trim()
        if (roomId.isEmpty()) throw invalidArgument("roomId is required")
        val start = Instant.ofEpochMilli(request.startTime)
        val end = Instant.ofEpochMilli(request.endTime)
        if (!end.isAfter(start)) throw invalidArgument("endTime must be after startTime")
        val overlaps = bookingRepository
            .findAllByRoomIdAndStartTimeLessThanAndEndTimeGreaterThan(roomId, end, start)
            .filter { it.id != existing?.id }
        val clash = overlaps.firstOrNull()
        if (clash != null) {
            throw conflict(
                "Room is already booked for that time",
                mapOf(
                    "conflictType" to "ROOM_OVERLAP",
                    "conflictingBookingId" to clash.clientId,
                    "conflictingBooking" to toBookingPayload(clash),
                ),
            )
        }
        val entity = existing ?: RoomBookingEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        entity.roomId = roomId
        entity.roomName = request.roomName.trim().ifEmpty { roomId }
        entity.teacherName = request.teacherName.trim().ifEmpty { teacher.name }
        entity.startTime = start
        entity.endTime = end
        entity.purpose = request.purpose.trim().takeIf { it.isNotEmpty() }
        entity.schoolId = teacher.schoolId
        bookingRepository.saveAndFlush(entity)
        return toBookingPayload(entity)
    }

    /** Repeat-safe cancellation. */
    @Transactional
    fun cancelBooking(teacher: UserEntity, bookingId: String) {
        requireTeacher(teacher)
        val entity = resolveBooking(teacher, bookingId) ?: return
        bookingRepository.delete(entity)
    }

    // ------------------------------------------------------------ internals

    internal fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    private fun entriesFor(teacher: UserEntity) =
        entryRepository.findAllByTeacherIdOrderByDayOfWeekAscStartTimeAsc(teacher.id)

    private fun resolveEntry(teacher: UserEntity, raw: String): TimetableEntryEntity? {
        val byClient = entryRepository.findByClientId(raw)
        if (byClient != null) {
            if (byClient.teacherId != teacher.id) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Not your timetable entry")
            }
            return byClient
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = entryRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your timetable entry")
        }
        return entity
    }

    private fun resolveBooking(teacher: UserEntity, raw: String): RoomBookingEntity? {
        val byClient = bookingRepository.findByClientId(raw)
        if (byClient != null) {
            if (byClient.teacherId != teacher.id) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Not your room booking")
            }
            return byClient
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = bookingRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your room booking")
        }
        return entity
    }

    private fun applyEntry(entity: TimetableEntryEntity, request: TimetableEntryPayload, teacher: UserEntity) {
        entity.teacherId = teacher.id
        entity.schoolId = teacher.schoolId
        entity.classId = request.classId.trim().takeIf { it.isNotEmpty() }
        entity.className = request.className.trim()
        entity.subject = request.subject.trim()
        entity.dayOfWeek = request.dayOfWeek.coerceIn(1, 7)
        entity.startTime = validateTime(request.startTime, "startTime")
        entity.endTime = validateTime(request.endTime, "endTime")
        entity.roomId = request.roomId?.trim()?.takeIf { it.isNotEmpty() }
        entity.roomName = request.roomName?.trim()?.takeIf { it.isNotEmpty() }
        entity.colorHex = request.colorHex?.trim()?.takeIf { it.isNotEmpty() }
        entity.houseId = request.houseId?.trim()?.takeIf { it.isNotEmpty() }
        entity.communityServiceId = request.communityServiceId?.trim()?.takeIf { it.isNotEmpty() }
        entity.peerCircleId = request.peerCircleId?.trim()?.takeIf { it.isNotEmpty() }
        entity.entryType = request.entryType.trim().ifEmpty { "LECTURE" }
        entity.practicalBlockType = validatePractical(request.practicalBlockType)
    }

    /**
     * A teacher cannot hold two overlapping slots on the same day. Mirrors the
     * client's in-memory [hasTeacherConflict] so a mutation rejected on one
     * device is rejected identically when replayed from another.
     */
    private fun rejectEntryClash(entity: TimetableEntryEntity) {
        val start = toMinutes(entity.startTime)
        val end = toMinutes(entity.endTime)
        val clash = entryRepository
            .findAllByTeacherIdAndDayOfWeekOrderByStartTimeAsc(entity.teacherId, entity.dayOfWeek)
            .firstOrNull { other ->
                other.clientId != entity.clientId &&
                    toMinutes(other.startTime) < end &&
                    start < toMinutes(other.endTime)
            } ?: return
        throw conflict(
            "You already have a class scheduled at that time",
            mapOf(
                "conflictType" to "TEACHER_OVERLAP",
                "conflictingEntryId" to clash.clientId,
                "conflictingEntry" to toEntryPayload(clash),
            ),
        )
    }

    private fun toMinutes(time: String): Int {
        val hour = time.substring(0, 2).toInt()
        val minute = time.substring(3, 5).toInt()
        return hour * 60 + minute
    }

    private fun toEntryPayload(entity: TimetableEntryEntity) = TimetableEntryPayload(
        id = entity.clientId,
        teacherId = entity.teacherId.toString(),
        classId = entity.classId ?: "",
        className = entity.className,
        subject = entity.subject,
        dayOfWeek = entity.dayOfWeek,
        startTime = entity.startTime,
        endTime = entity.endTime,
        roomId = entity.roomId,
        roomName = entity.roomName,
        colorHex = entity.colorHex,
        houseId = entity.houseId,
        communityServiceId = entity.communityServiceId,
        peerCircleId = entity.peerCircleId,
        entryType = entity.entryType,
        practicalBlockType = entity.practicalBlockType,
    )

    private fun toBookingPayload(entity: RoomBookingEntity) = RoomBookingPayload(
        id = entity.clientId,
        roomId = entity.roomId,
        roomName = entity.roomName,
        teacherId = entity.teacherId.toString(),
        teacherName = entity.teacherName ?: "",
        startTime = entity.startTime.toEpochMilli(),
        endTime = entity.endTime.toEpochMilli(),
        purpose = entity.purpose ?: "",
    )

    private fun validateTime(raw: String, field: String): String {
        val value = raw.trim()
        if (!TIME_PATTERN.matches(value)) throw invalidArgument(field + " must be HH:mm")
        return value
    }

    private fun validatePractical(raw: String): String {
        val value = raw.trim().uppercase()
        if (value !in PRACTICAL_TYPES) throw invalidArgument("Unknown practicalBlockType: " + raw)
        return value
    }

    private fun practicalFor(subject: String): Practical {
        val value = subject.lowercase()
        return when {
            listOf("biolog", "chemist", "physic", "science").any { value.contains(it) } ->
                Practical("LAB_PERIOD", "PRACTICAL")
            listOf("agricultur", "farm").any { value.contains(it) } ->
                Practical("FIELD_WORK", "PRACTICAL")
            listOf("community", "social", "service", "civic").any { value.contains(it) } ->
                Practical("COMMUNITY_WORKSHOP", "COMMUNITY_SERVICE")
            else -> Practical("NONE", "LECTURE")
        }
    }

    private fun formatMinutes(minutes: Int): String =
        String.format("%02d:%02d", minutes / 60, minutes % 60)

    private data class Practical(val type: String, val entryType: String)

    private companion object {
        const val MONDAY = 1
        val TIME_PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        val PRACTICAL_TYPES = setOf("LAB_PERIOD", "FIELD_WORK", "COMMUNITY_WORKSHOP", "NONE")
    }
}
