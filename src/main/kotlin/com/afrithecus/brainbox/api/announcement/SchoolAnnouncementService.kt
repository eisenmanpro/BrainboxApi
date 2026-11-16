package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.entity.SchoolAnnouncementEntity
import com.afrithecus.brainbox.api.announcement.entity.TeacherAnnouncementEntity
import com.afrithecus.brainbox.api.announcement.repository.SchoolAnnouncementRepository
import com.afrithecus.brainbox.api.announcement.repository.TeacherAnnouncementRepository
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementAnalyticsPayload
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementPayload
import com.afrithecus.brainbox.api.announcement.web.SchoolAnnouncementReachPayload
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * School-wide announcements authored by an admin (docs/ongoing/open_gaps.md ANN-1) with
 * structured targeting (findings round "ADMIN ANNOUNCEMENTS 1").
 *
 * Two layers exist on purpose:
 *  - this admin-owned record keeps the whole studio history, meeting fields and reach estimate;
 *  - a **delivery mirror** in `teacher_announcements` (the inbox teachers and learners already
 *    read through `/teacher/announcements/received` and `/announcements/for-me`) makes the
 *    targeting real. The mirror is keyed by `sourceSchoolAnnouncementId`, so an edit refreshes
 *    the delivered copy and a delete removes it — there is no second, divergent inbox.
 *
 * Legacy rows (no `targetType`) keep the pre-existing behaviour: stored + reach-estimated from
 * the free-text audience, exactly as before, so old data and older clients do not change shape.
 */
@Service
class SchoolAnnouncementService(
    private val repository: SchoolAnnouncementRepository,
    private val schoolRepository: SchoolRepository,
    private val userRepository: UserRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val announcementRepository: TeacherAnnouncementRepository,
    private val codec: QuestionCodec,
    private val auditLogService: AuditLogService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(schoolIdRaw: String): List<SchoolAnnouncementPayload> {
        val schoolId = schoolId(schoolIdRaw)
        val rows = repository.findAllBySchoolIdOrderByPostedAtDesc(schoolId)
        val roster = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val memberships = membershipsFor(classes)
        return rows.map { payload(it, roster, classes, memberships) }
    }

    /**
     * Authoring totals plus a per-announcement audience reach estimate for the admin
     * studio. Reach derives from the structured target when one is present, else from the
     * stored audience string (legacy). No per-recipient view tracking exists for school
     * announcements, so no view/acknowledgement counters are reported here.
     */
    @Transactional(readOnly = true)
    fun analytics(schoolIdRaw: String): SchoolAnnouncementAnalyticsPayload {
        val schoolId = schoolId(schoolIdRaw)
        val all = repository.findAllBySchoolIdOrderByPostedAtDesc(schoolId)
        val roster = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        val memberships = membershipsFor(classes)
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
                    estimatedReach = reach(entity, roster, classes, memberships),
                )
            },
        )
    }

    @Transactional
    fun create(admin: UserEntity, schoolIdRaw: String, request: SchoolAnnouncementPayload): SchoolAnnouncementPayload {
        val schoolId = schoolId(schoolIdRaw)
        val target = normalizeTarget(schoolId, request)
        validate(request, target)
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
        applyTarget(entity, target)
        repository.saveAndFlush(entity)
        syncDelivery(entity)
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
        val target = normalizeTarget(schoolId, request)
        validate(request, target)
        val entity = own(announcementIdRaw, schoolId)
        entity.title = request.title.trim()
        entity.body = request.body.trim()
        entity.audience = request.audience.trim()
        entity.scheduledMeetingDate = request.scheduledMeetingDate?.let(java.time.Instant::ofEpochMilli)
        entity.meetingTitle = request.meetingTitle?.trim()?.takeIf { it.isNotEmpty() }
        applyTarget(entity, target)
        entity.updatedAt = clock.instant()
        repository.saveAndFlush(entity)
        syncDelivery(entity)
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
        announcementRepository.findAllBySourceSchoolAnnouncementId(entity.id).forEach(announcementRepository::delete)
        repository.delete(entity)
        auditLogService.record(schoolId, admin, "Deleted announcement '" + entity.title + "'")
    }

    // ------------------------------------------------------------ targeting

    private data class Target(
        val type: String?,
        val gradeLevels: List<String> = emptyList(),
        val classIds: List<UUID> = emptyList(),
        val teacherIds: List<UUID> = emptyList(),
        val studentIds: List<UUID> = emptyList(),
    )

    /**
     * Normalises and validates the structured target. A request with no structured fields
     * (`targetType` absent and every list empty) is a legacy payload: the target is null and
     * the stored audience string keeps working exactly as before.
     */
    private fun normalizeTarget(schoolId: UUID, request: SchoolAnnouncementPayload): Target {
        val rawType = request.targetType?.trim()?.takeIf { it.isNotEmpty() }
        val hasLists = request.targetGradeLevels.isNotEmpty() || request.targetClassIds.isNotEmpty() ||
            request.targetTeacherIds.isNotEmpty() || request.targetStudentIds.isNotEmpty()
        if (rawType == null && !hasLists) return Target(type = null)
        val type = (rawType ?: inferLegacyType(request.audience)).trim().uppercase()
        if (type !in TARGET_TYPES) throw invalidArgument("targetType must be one of " + TARGET_TYPES.joinToString(", "))
        val grades = request.targetGradeLevels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val classIds = request.targetClassIds.mapNotNull { parseUuid(it, "targetClassIds") }.distinct()
        val teacherIds = request.targetTeacherIds.mapNotNull { parseUuid(it, "targetTeacherIds") }.distinct()
        val studentIds = request.targetStudentIds.mapNotNull { parseUuid(it, "targetStudentIds") }.distinct()
        when (type) {
            "GRADE" -> if (grades.isEmpty()) throw invalidArgument("A grade-targeted announcement needs targetGradeLevels")
            "CLASS" -> if (classIds.isEmpty()) throw invalidArgument("A class-targeted announcement needs targetClassIds")
            "INDIVIDUALS" -> if (teacherIds.isEmpty() && studentIds.isEmpty()) {
                throw invalidArgument("An individual-targeted announcement needs targetTeacherIds or targetStudentIds")
            }
        }
        requireTargetsInSchool(schoolId, classIds, teacherIds, studentIds)
        return Target(type, grades, classIds, teacherIds, studentIds)
    }

    /** A payload with only lists (no type) infers the narrowest applicable target. */
    private fun inferLegacyType(audience: String): String {
        val lower = audience.lowercase()
        return when {
            lower.contains("teacher") || lower.contains("staff") -> "TEACHERS"
            lower.contains("grade") -> "GRADE"
            lower.contains("class") -> "CLASS"
            lower.contains("parent") || lower.contains("student") || lower.contains("learner") -> "PARENTS"
            else -> "ALL"
        }
    }

    private fun applyTarget(entity: SchoolAnnouncementEntity, target: Target) {
        entity.targetType = target.type
        entity.targetGradeLevels = codec.toJson(target.gradeLevels)
        entity.targetClassIds = codec.toJson(target.classIds.map { it.toString() })
        entity.targetTeacherIds = codec.toJson(target.teacherIds.map { it.toString() })
        entity.targetStudentIds = codec.toJson(target.studentIds.map { it.toString() })
    }

    private fun requireTargetsInSchool(
        schoolId: UUID,
        classIds: List<UUID>,
        teacherIds: List<UUID>,
        studentIds: List<UUID>,
    ) {
        if (classIds.isNotEmpty()) {
            val rows = classRepository.findAllById(classIds)
            if (rows.size != classIds.size || rows.any { it.schoolId != schoolId }) {
                throw invalidArgument("targetClassIds must be classes of this school")
            }
        }
        if (teacherIds.isNotEmpty()) {
            val rows = userRepository.findAllById(teacherIds)
            if (rows.size != teacherIds.size || rows.any { it.schoolId != schoolId || it.role != Role.TEACHER }) {
                throw invalidArgument("targetTeacherIds must be teachers of this school")
            }
        }
        if (studentIds.isNotEmpty()) {
            val rows = userRepository.findAllById(studentIds)
            if (rows.size != studentIds.size || rows.any { it.schoolId != schoolId || it.role != Role.STUDENT }) {
                throw invalidArgument("targetStudentIds must be learners of this school")
            }
        }
    }

    // ------------------------------------------------------------ delivery

    /**
     * Rewrites the delivered inbox copy so the recipients the admin targeted actually see
     * the announcement in their existing feeds. One row per audience channel: a staff row
     * for teacher targeting and a learner row for learner/parent targeting (F9: the learner
     * account is the guardian account). Rows are matched by `sourceSchoolAnnouncementId`,
     * so this is idempotent across edits.
     */
    private fun syncDelivery(entity: SchoolAnnouncementEntity) {
        val existing = announcementRepository.findAllBySourceSchoolAnnouncementId(entity.id).associateBy { it.audience }
        val channels = deliveryChannels(entity)
        // Remove rows for a channel the announcement no longer targets.
        existing.filterKeys { it !in channels.keys }.values.forEach(announcementRepository::delete)
        channels.forEach { (audience, channel) ->
            val row = existing[audience] ?: TeacherAnnouncementEntity().apply {
                clientId = "school_ann_" + entity.id + "_" + audience.lowercase()
                teacherId = entity.postedBy ?: entity.schoolId
                schoolId = entity.schoolId
                sentAt = entity.postedAt
                sourceSchoolAnnouncementId = entity.id
            }
            row.title = entity.title
            row.content = entity.body
            row.announcementType = "NOTICE"
            row.audience = audience
            row.targetClassIds = codec.toJson(channel.classIds.map { it.toString() })
            row.targetGradeLevels = codec.toJson(channel.gradeLevels)
            row.targetStudentIds = codec.toJson(channel.studentIds.map { it.toString() })
            row.targetTeacherIds = codec.toJson(channel.teacherIds.map { it.toString() })
            row.isPriority = false
            row.deliveredAt = clock.instant()
            announcementRepository.save(row)
        }
    }

    private data class DeliveryChannel(
        val classIds: List<UUID> = emptyList(),
        val gradeLevels: List<String> = emptyList(),
        val studentIds: List<UUID> = emptyList(),
        val teacherIds: List<UUID> = emptyList(),
    )

    /** The inbox row(s) a structured target maps to, keyed by the delivery channel audience. */
    private fun deliveryChannels(entity: SchoolAnnouncementEntity): Map<String, DeliveryChannel> {
        val type = entity.targetType ?: return emptyMap()
        val grades = codec.parseList(entity.targetGradeLevels).orEmpty().mapNotNull { GradeNormalizer.canonicalKey(it)?.toString() }
        val classIds = uuidList(entity.targetClassIds)
        val teacherIds = uuidList(entity.targetTeacherIds)
        val studentIds = uuidList(entity.targetStudentIds)
        return when (type) {
            "ALL" -> mapOf("SCHOOL" to DeliveryChannel())
            "TEACHERS" -> mapOf("TEACHER" to DeliveryChannel(teacherIds = teacherIds))
            "PARENTS", "LEARNERS" -> mapOf("PARENT" to DeliveryChannel(studentIds = studentIds))
            "GRADE" -> mapOf("GRADE" to DeliveryChannel(gradeLevels = grades))
            "CLASS" -> mapOf("CLASS" to DeliveryChannel(classIds = classIds))
            "INDIVIDUALS" -> buildMap {
                if (teacherIds.isNotEmpty()) put("TEACHER", DeliveryChannel(teacherIds = teacherIds))
                if (studentIds.isNotEmpty()) put("PARENT", DeliveryChannel(studentIds = studentIds))
            }
            else -> emptyMap()
        }
    }

    // ------------------------------------------------------------ reach

    /** Estimated active recipients, from the structured target when present else the legacy string. */
    private fun reach(
        entity: SchoolAnnouncementEntity,
        roster: List<UserEntity>,
        classes: List<TeacherClassEntity>,
        memberships: Map<UUID, List<UUID>>,
    ): Int {
        val type = entity.targetType ?: return legacyReach(entity.audience, roster)
        if (roster.isEmpty()) return 0
        val learners = roster.filter { it.role == Role.STUDENT && it.accountKind == AccountKind.FULL }
        val teachers = roster.filter { it.role == Role.TEACHER }
        val classIds = uuidList(entity.targetClassIds)
        val teacherIds = uuidList(entity.targetTeacherIds).toSet()
        val studentIds = uuidList(entity.targetStudentIds).toSet()
        val grades = codec.parseList(entity.targetGradeLevels).orEmpty()
            .mapNotNull { GradeNormalizer.canonicalKey(it) }.toSet()
        return when (type) {
            "ALL" -> roster.size
            "TEACHERS" -> if (teacherIds.isEmpty()) teachers.size else teachers.count { it.id in teacherIds }
            "PARENTS", "LEARNERS" -> if (studentIds.isEmpty()) learners.size else learners.count { it.id in studentIds }
            "GRADE" -> learners.count { learner ->
                GradeNormalizer.canonicalKey(learner.gradeLevel) in grades ||
                    memberships[learner.id].orEmpty().any { classId ->
                        GradeNormalizer.canonicalKey(classes.firstOrNull { it.id == classId }?.gradeLevel) in grades
                    }
            }
            "CLASS" -> {
                val members = classIds.flatMap { memberships[it].orEmpty() }.toSet()
                learners.count { it.id in members }
            }
            "INDIVIDUALS" -> teachers.count { it.id in teacherIds } + learners.count { it.id in studentIds }
            else -> roster.size
        }
    }

    private fun legacyReach(audience: String, roster: List<UserEntity>): Int {
        if (roster.isEmpty()) return 0
        val trimmed = audience.trim()
        if (trimmed.isEmpty() || trimmed.equals("All", ignoreCase = true) || trimmed.equals("Custom", ignoreCase = true)) {
            return roster.size
        }
        val parts = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.none { it.contains(":") }) return legacyAudienceCount(trimmed, roster)
        val targeted = LinkedHashSet<UUID>()
        parts.forEach { part ->
            when {
                part.startsWith("Grades:", ignoreCase = true) -> {
                    val wanted = numericTokens(part.substringAfter(":"))
                    if (wanted.isNotEmpty()) {
                        targeted += roster.filter {
                            it.role == Role.STUDENT && it.accountKind == AccountKind.FULL &&
                                numericTokens(it.gradeLevel.orEmpty()).any { grade -> grade in wanted }
                        }.map { it.id }
                    }
                }
                part.startsWith("Teachers:", ignoreCase = true) -> {
                    val names = nameList(part.substringAfter(":"))
                    targeted += roster.filter { it.name in names }.map { it.id }
                }
                part.startsWith("Classes:", ignoreCase = true) -> {
                    val names = nameList(part.substringAfter(":"))
                    val classIds = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(
                        roster.firstOrNull()?.schoolId ?: return@forEach,
                    ).filter { it.name in names }.map { it.id }
                    val studentIds = classIds.flatMap { id ->
                        membershipRepository.findAllByClassId(id).map { it.studentId }
                    }.toSet()
                    targeted += roster.filter { it.id in studentIds }.map { it.id }
                }
            }
        }
        return if (targeted.isEmpty()) roster.size else targeted.size
    }

    private fun legacyAudienceCount(audience: String, roster: List<UserEntity>): Int {
        val lower = audience.lowercase()
        return when {
            // F9: "parents" is the guardian channel of the learner account.
            lower.contains("parent") -> roster.count { it.role == Role.STUDENT && it.accountKind == AccountKind.FULL }
            lower.contains("student") -> roster.count { it.role == Role.STUDENT && it.accountKind == AccountKind.FULL }
            lower.contains("teacher") || lower.contains("staff") -> roster.count { it.role == Role.TEACHER }
            else -> roster.size
        }
    }

    private fun membershipsFor(classes: List<TeacherClassEntity>): Map<UUID, List<UUID>> {
        if (classes.isEmpty()) return emptyMap()
        return membershipRepository.findAllByClassIdIn(classes.map { it.id })
            .groupBy({ it.classId }, { it.studentId })
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

    private fun validate(request: SchoolAnnouncementPayload, target: Target) {
        if (request.title.isBlank()) throw invalidArgument("announcement title is required")
        if (request.body.isBlank()) throw invalidArgument("announcement body is required")
        // The legacy audience string is only required when no structured target replaces it.
        if (target.type == null && request.audience.isBlank()) {
            throw invalidArgument("announcement audience is required")
        }
    }

    private fun payload(entity: SchoolAnnouncementEntity): SchoolAnnouncementPayload {
        val roster = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(entity.schoolId)
        val classes = classRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(entity.schoolId)
        return payload(entity, roster, classes, membershipsFor(classes))
    }

    private fun payload(
        entity: SchoolAnnouncementEntity,
        roster: List<UserEntity>,
        classes: List<TeacherClassEntity>,
        memberships: Map<UUID, List<UUID>>,
    ) = SchoolAnnouncementPayload(
        announcementId = entity.id.toString(),
        title = entity.title,
        body = entity.body,
        audience = entity.audience,
        postedBy = entity.postedByName.orEmpty(),
        postedAt = entity.postedAt.toEpochMilli(),
        scheduledMeetingDate = entity.scheduledMeetingDate?.toEpochMilli(),
        meetingTitle = entity.meetingTitle,
        targetType = entity.targetType,
        targetGradeLevels = codec.parseList(entity.targetGradeLevels).orEmpty(),
        targetClassIds = codec.parseList(entity.targetClassIds).orEmpty(),
        targetTeacherIds = codec.parseList(entity.targetTeacherIds).orEmpty(),
        targetStudentIds = codec.parseList(entity.targetStudentIds).orEmpty(),
        estimatedReach = reach(entity, roster, classes, memberships),
    )

    private fun uuidList(json: String?): List<UUID> =
        codec.parseList(json).orEmpty().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun nameList(raw: String): Set<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun numericTokens(raw: String): Set<Int> =
        Regex("""[0-9]+""").findAll(raw).map { it.value.toInt() }.toSet()

    private fun parseUuid(raw: String, field: String): UUID? =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: run { if (raw.isBlank()) null else throw invalidArgument(field + " is not a valid identifier") }

    private companion object {
        /** The structured audiences the admin studio can address. */
        val TARGET_TYPES = setOf("ALL", "TEACHERS", "PARENTS", "LEARNERS", "GRADE", "CLASS", "INDIVIDUALS")
    }
}
