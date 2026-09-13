package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.entity.SchoolAnnouncementEntity
import com.afrithecus.brainbox.api.announcement.repository.SchoolAnnouncementRepository
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementPayload
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * School-wide announcements authored by an admin (docs/ongoing/open_gaps.md ANN-1).
 * The admin studio is offline-first, so deletes are repeat-safe and updates keep
 * the original author/timestamp.
 */
@Service
class SchoolAnnouncementService(
    private val repository: SchoolAnnouncementRepository,
    private val schoolRepository: SchoolRepository,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(schoolIdRaw: String): List<SchoolAnnouncementPayload> {
        val schoolId = schoolId(schoolIdRaw)
        return repository.findAllBySchoolIdOrderByPostedAtDesc(schoolId).map(::payload)
    }

    @Transactional
    fun create(admin: UserEntity, schoolIdRaw: String, request: SchoolAnnouncementPayload): SchoolAnnouncementPayload {
        val schoolId = schoolId(schoolIdRaw)
        validate(request)
        val entity = SchoolAnnouncementEntity().apply {
            this.schoolId = schoolId
            title = request.title.trim()
            body = request.body.trim()
            audience = request.audience.trim()
            postedBy = admin.id
            postedByName = admin.name
            postedAt = clock.instant()
            scheduledMeetingDate = request.scheduledMeetingDate?.let(java.time.Instant::ofEpochMilli)
            meetingTitle = request.meetingTitle?.trim()?.takeIf { it.isNotEmpty() }
        }
        repository.saveAndFlush(entity)
        auditLogService.record(schoolId, admin, "Created announcement '" + entity.title + "'")
        return payload(entity)
    }

    @Transactional
    fun update(
        admin: UserEntity,
        schoolIdRaw: String,
        announcementIdRaw: String,
        request: SchoolAnnouncementPayload,
    ): SchoolAnnouncementPayload {
        val schoolId = schoolId(schoolIdRaw)
        validate(request)
        val entity = own(announcementIdRaw, schoolId)
        entity.title = request.title.trim()
        entity.body = request.body.trim()
        entity.audience = request.audience.trim()
        entity.scheduledMeetingDate = request.scheduledMeetingDate?.let(java.time.Instant::ofEpochMilli)
        entity.meetingTitle = request.meetingTitle?.trim()?.takeIf { it.isNotEmpty() }
        entity.updatedAt = clock.instant()
        repository.saveAndFlush(entity)
        auditLogService.record(schoolId, admin, "Updated announcement '" + entity.title + "'")
        return payload(entity)
    }

    /** Repeat-safe: an unknown announcement is treated as already deleted. */
    @Transactional
    fun delete(admin: UserEntity, schoolIdRaw: String, announcementIdRaw: String) {
        val schoolId = schoolId(schoolIdRaw)
        val id = parseUuid(announcementIdRaw, "announcementId") ?: return
        val entity = repository.findById(id).orElse(null) ?: return
        if (entity.schoolId != schoolId) return
        repository.delete(entity)
        auditLogService.record(schoolId, admin, "Deleted announcement '" + entity.title + "'")
    }

    // ------------------------------------------------------------ internals

    private fun schoolId(raw: String): UUID {
        val id = parseUuid(raw, "schoolId") ?: throw invalidArgument("schoolId is not a valid identifier")
        schoolRepository.findById(id).orElse(null) ?: throw notFound("School not found")
        return id
    }

    private fun own(announcementIdRaw: String, schoolId: UUID): SchoolAnnouncementEntity {
        val id = parseUuid(announcementIdRaw, "announcementId")
            ?: throw invalidArgument("announcementId is not a valid identifier")
        val entity = repository.findById(id).orElse(null) ?: throw notFound("Announcement not found")
        if (entity.schoolId != schoolId) throw notFound("Announcement not found")
        return entity
    }

    private fun validate(request: SchoolAnnouncementPayload) {
        if (request.title.isBlank()) throw invalidArgument("announcement title is required")
        if (request.body.isBlank()) throw invalidArgument("announcement body is required")
        if (request.audience.isBlank()) throw invalidArgument("announcement audience is required")
    }

    private fun payload(entity: SchoolAnnouncementEntity) = SchoolAnnouncementPayload(
        announcementId = entity.id.toString(),
        title = entity.title,
        body = entity.body,
        audience = entity.audience,
        postedBy = entity.postedByName.orEmpty(),
        postedAt = entity.postedAt.toEpochMilli(),
        scheduledMeetingDate = entity.scheduledMeetingDate?.toEpochMilli(),
        meetingTitle = entity.meetingTitle,
    )

    private fun parseUuid(raw: String, field: String): UUID? =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: run { if (raw.isBlank()) null else throw invalidArgument(field + " is not a valid identifier") }
}
