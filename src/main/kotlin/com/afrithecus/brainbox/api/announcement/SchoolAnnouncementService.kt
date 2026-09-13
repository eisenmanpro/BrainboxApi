package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.entity.SchoolAnnouncementEntity
import com.afrithecus.brainbox.api.announcement.repository.SchoolAnnouncementRepository
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementAnalyticsPayload
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementPayload
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementReachPayload
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
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
    private val userRepository: UserRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(schoolIdRaw: String): List<SchoolAnnouncementPayload> {
        val schoolId = schoolId(schoolIdRaw)
        return repository.findAllBySchoolIdOrderByPostedAtDesc(schoolId).map(::payload)
    }

    /**
     * Authoring totals plus a per-announcement audience reach estimate for the
     * admin studio. Reach derives from the stored audience string and the
     * school roster; no per-recipient view tracking exists for school
     * announcements yet, so no view/acknowledgement counters are reported.
     */
    @Transactional(readOnly = true)
    fun analytics(schoolIdRaw: String): SchoolAnnouncementAnalyticsPayload {
        val schoolId = schoolId(schoolIdRaw)
        val all = repository.findAllBySchoolIdOrderByPostedAtDesc(schoolId)
        val roster = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val cutoff = clock.instant().minus(Duration.ofDays(30))
        return SchoolAnnouncementAnalyticsPayload(
            schoolId = schoolId.toString(),
            totalAnnouncements = all.size,
            announcementsLast30Days = all.count { it.postedAt.isAfter(cutoff) },
            scheduledMeetings = all.count { it.scheduledMeetingDate != null },
            latestPostedAt = all.firstOrNull()?.postedAt?.toEpochMilli(),
            announcements = all.map { entity ->
                SchoolAnnouncementReachPayload(
                    announcementId = entity.id.toString(),
                    title = entity.title,
                    audience = entity.audience,
                    postedAt = entity.postedAt.toEpochMilli(),
                    estimatedReach = estimatedReach(entity.audience, roster, classes),
                )
            },
        )
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

    private fun estimatedReach(
        audience: String,
        roster: List<UserEntity>,
        classes: List<TeacherClassEntity>,
    ): Int {
        if (roster.isEmpty()) return 0
        val trimmed = audience.trim()
        if (trimmed.isEmpty() || trimmed.equals("All", ignoreCase = true) || trimmed.equals("Custom", ignoreCase = true)) {
            return roster.size
        }
        val parts = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.none { it.contains(":") }) return legacyReach(trimmed, roster)
        val targeted = LinkedHashSet<UUID>()
        parts.forEach { part ->
            when {
                part.startsWith("Grades:", ignoreCase = true) -> {
                    val grades = numericTokens(part.substringAfter(":"))
                    if (grades.isNotEmpty()) {
                        val students = roster.filter {
                            it.role == Role.STUDENT && numericTokens(it.gradeLevel.orEmpty()).any { grade -> grade in grades }
                        }
                        targeted += students.map { it.id }
                        targeted += parentsOf(students, roster)
                    }
                }
                part.startsWith("Teachers:", ignoreCase = true) -> {
                    val wanted = nameList(part.substringAfter(":"))
                    targeted += roster.filter { it.name in wanted }.map { it.id }
                }
                part.startsWith("Classes:", ignoreCase = true) -> {
                    val wanted = nameList(part.substringAfter(":"))
                    val studentIds = classes
                        .filter { it.name in wanted }
                        .flatMap { clazz -> membershipRepository.findAllByClassId(clazz.id).map { it.studentId } }
                        .toSet()
                    val students = roster.filter { it.id in studentIds }
                    targeted += students.map { it.id }
                    targeted += parentsOf(students, roster)
                }
            }
        }
        return if (targeted.isEmpty()) roster.size else targeted.size
    }

    private fun legacyReach(audience: String, roster: List<UserEntity>): Int {
        val lower = audience.lowercase()
        return when {
            lower.contains("parent") -> roster.count { it.role == Role.PARENT }
            lower.contains("student") -> roster.count { it.role == Role.STUDENT }
            lower.contains("teacher") || lower.contains("staff") -> roster.count { it.role == Role.TEACHER }
            else -> roster.size
        }
    }

    private fun parentsOf(students: List<UserEntity>, roster: List<UserEntity>): Set<UUID> {
        val parentIds = students.mapNotNull { it.parentUserId }.toSet()
        return roster.filter { it.role == Role.PARENT && it.id in parentIds }.map { it.id }.toSet()
    }

    private fun nameList(raw: String): Set<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun numericTokens(raw: String): Set<Int> =
        Regex("""[0-9]+""").findAll(raw).map { it.value.toInt() }.toSet()

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
