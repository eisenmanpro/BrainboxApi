package com.afrithecus.brainbox.api.timetable

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.timetable.entity.ScheduleChangeEntity
import com.afrithecus.brainbox.api.timetable.repository.ScheduleChangeRepository
import com.afrithecus.brainbox.api.timetable.web.ScheduleChangePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Schedule change requests (doc 04 section 9.5): a teacher submits a move or
 * cover request and a grade coordinator / ICT admin approves or rejects it.
 * Submission is idempotent on the client id for offline replay.
 */
@Service
class ScheduleChangeService(
    private val repository: ScheduleChangeRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {

    @Transactional
    fun submit(teacher: UserEntity, request: ScheduleChangePayload): ScheduleChangePayload {
        requireTeacher(teacher)
        validate(request)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "sch_" + UUID.randomUUID()
        val existing = repository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw forbidden("Not your schedule change request")
        }
        if (existing != null && existing.status != PENDING) {
            // Already decided; a replayed offline submit must not reopen it.
            return payload(existing)
        }
        val entity = existing ?: ScheduleChangeEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        entity.teacherId = teacher.id
        entity.schoolId = teacher.schoolId
        entity.teacherName = teacher.name
        entity.day = request.day.trim()
        entity.className = request.className.trim()
        entity.subject = request.subject.trim()
        entity.startTime = request.startTime.trim()
        entity.endTime = request.endTime.trim()
        entity.reason = request.reason.trim()
        entity.status = PENDING
        entity.requestedAt =
            if (request.requestedAt > 0) Instant.ofEpochMilli(request.requestedAt) else clock.instant()
        entity.decidedAt = null
        entity.decidedBy = null
        entity.decisionNote = null
        repository.saveAndFlush(entity)
        return payload(entity)
    }

    /**
     * A teacher sees their own history; a coordinator with no teacherId sees the
     * whole school and may drill into one teacher.
     */
    @Transactional(readOnly = true)
    fun list(teacher: UserEntity, teacherIdQuery: String?): List<ScheduleChangePayload> {
        requireTeacher(teacher)
        if (isStaff(teacher)) {
            if (teacherIdQuery.isNullOrBlank()) {
                val schoolId = teacher.schoolId
                val rows = if (schoolId != null) {
                    repository.findAllBySchoolIdOrderByRequestedAtDesc(schoolId)
                } else {
                    repository.findAllByTeacherIdOrderByRequestedAtDesc(teacher.id)
                }
                return rows.map(::payload)
            }
            val targetId = parseUuid(teacherIdQuery, "teacherId")
            val target = userRepository.findById(targetId).orElse(null)
                ?: throw notFound("Teacher not found")
            if (teacher.schoolId != null && target.schoolId != teacher.schoolId) {
                throw forbidden("Teacher is not in your school")
            }
            return repository.findAllByTeacherIdOrderByRequestedAtDesc(targetId).map(::payload)
        }
        if (!teacherIdQuery.isNullOrBlank() && teacherIdQuery != teacher.id.toString()) {
            throw forbidden("You can only view your own schedule changes")
        }
        return repository.findAllByTeacherIdOrderByRequestedAtDesc(teacher.id).map(::payload)
    }

    @Transactional
    fun approve(teacher: UserEntity, id: String): ScheduleChangePayload = decide(teacher, id, APPROVED)

    @Transactional
    fun reject(teacher: UserEntity, id: String): ScheduleChangePayload = decide(teacher, id, REJECTED)

    // ------------------------------------------------------------ internals

    private fun decide(teacher: UserEntity, id: String, status: String): ScheduleChangePayload {
        requireTeacher(teacher)
        if (!isStaff(teacher)) throw forbidden("Only a coordinator can review schedule changes")
        val entity = resolve(id) ?: throw notFound("Schedule change not found")
        if (teacher.schoolId != null && entity.schoolId != null && entity.schoolId != teacher.schoolId) {
            throw forbidden("Schedule change is not in your school")
        }
        entity.status = status
        entity.decidedAt = clock.instant()
        entity.decidedBy = teacher.id
        repository.saveAndFlush(entity)
        return payload(entity)
    }

    private fun resolve(raw: String): ScheduleChangeEntity? {
        repository.findByClientId(raw)?.let { return it }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return repository.findById(id).orElse(null)
    }

    private fun validate(request: ScheduleChangePayload) {
        if (request.day.isBlank()) throw invalidArgument("day is required")
        if (request.className.isBlank()) throw invalidArgument("className is required")
        if (request.subject.isBlank()) throw invalidArgument("subject is required")
        if (request.reason.isBlank()) throw invalidArgument("reason is required")
        if (!TIME_PATTERN.matches(request.startTime.trim())) throw invalidArgument("startTime must be HH:mm")
        if (!TIME_PATTERN.matches(request.endTime.trim())) throw invalidArgument("endTime must be HH:mm")
    }

    private fun payload(entity: ScheduleChangeEntity) = ScheduleChangePayload(
        id = entity.clientId,
        teacherId = entity.teacherId.toString(),
        teacherName = entity.teacherName ?: "",
        day = entity.day,
        className = entity.className,
        subject = entity.subject,
        startTime = entity.startTime,
        endTime = entity.endTime,
        reason = entity.reason ?: "",
        status = entity.status,
        requestedAt = entity.requestedAt.toEpochMilli(),
    )

    private fun isStaff(user: UserEntity): Boolean =
        user.subRole == SubRole.GRADE_COORDINATOR || user.subRole == SubRole.ICT_ADMIN

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw forbidden("Teacher access only")
    }

    private companion object {
        const val PENDING = "PENDING"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        val TIME_PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    }
}
