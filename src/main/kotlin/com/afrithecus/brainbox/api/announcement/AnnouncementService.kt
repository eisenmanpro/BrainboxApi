package com.afrithecus.brainbox.api.announcement

import com.afrithecus.brainbox.api.announcement.entity.TeacherAnnouncementEntity
import com.afrithecus.brainbox.api.announcement.repository.AnnouncementViewRepository
import com.afrithecus.brainbox.api.announcement.repository.TeacherAnnouncementRepository
import com.afrithecus.brainbox.api.announcement.web.AnnouncementAnalyticsPayload
import com.afrithecus.brainbox.api.announcement.web.StudentAcknowledgementStatusPayload
import com.afrithecus.brainbox.api.announcement.web.TeacherAnnouncementPayload
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Teacher announcements (doc 04 section 6, docs/ongoing/api_announcement_changes.md):
 * idempotent client-id CRUD, scheduled hold and expiry, audience fan-out over the
 * notification service, and per-announcement analytics.
 */
@Service
class AnnouncementService(
    private val announcementRepository: TeacherAnnouncementRepository,
    private val viewRepository: AnnouncementViewRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService,
    private val codec: QuestionCodec,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun own(teacher: UserEntity): List<TeacherAnnouncementPayload> {
        requireTeacher(teacher)
        return announcementRepository.findAllByTeacherIdOrderBySentAtDesc(teacher.id).map(::payload)
    }

    /** Announcements authored by other staff in the same school, delivered and unexpired. */
    @Transactional(readOnly = true)
    fun received(teacher: UserEntity): List<TeacherAnnouncementPayload> {
        val schoolId = teacher.schoolId ?: return emptyList()
        val now = clock.instant()
        return announcementRepository.findAllByTeacherIdNotAndSchoolIdOrderBySentAtDesc(teacher.id, schoolId)
            .filter { it.deliveredAt != null && !hidden(it, now) }
            .map(::payload)
    }

    /**
     * Announcements targeting the signed-in learner (or a parent's children):
     * delivered, unexpired and matching the account's school, grade, class or
     * explicit student target.
     */
    @Transactional(readOnly = true)
    fun forMe(user: UserEntity, grade: String?): List<TeacherAnnouncementPayload> {
        val schoolId = user.schoolId ?: return emptyList()
        val now = clock.instant()
        val subjectIds: Set<UUID> = if (user.role == Role.PARENT) {
            userRepository.findByParentUserId(user.id).map { it.id }.toSet() + user.id
        } else {
            setOf(user.id)
        }
        val subjects = userRepository.findAllById(subjectIds)
        val classIds = subjects
            .flatMap { subject ->
                membershipRepository.findAllByStudentId(subject.id).map { it.classId.toString() }
            }
            .toSet()
        val grades = subjects.mapNotNull { GradeNormalizer.canonicalKey(it.gradeLevel) }.toMutableSet()
        GradeNormalizer.canonicalKey(grade)?.let { grades += it }
        return announcementRepository.findAllBySchoolIdAndDeliveredAtIsNotNullOrderBySentAtDesc(schoolId)
            .filter { !hidden(it, now) }
            .filter { visibleTo(it, user, subjectIds, classIds, grades) }
            .map(::payload)
    }

    /** Idempotent upsert keyed on the client-supplied id. */
    @Transactional
    fun create(teacher: UserEntity, request: TeacherAnnouncementPayload): TeacherAnnouncementPayload {
        requireTeacher(teacher)
        validate(request)
        val clientId = request.id.takeIf { it.isNotBlank() } ?: "ann_" + UUID.randomUUID()
        val existing = announcementRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your announcement")
        val entity = existing ?: TeacherAnnouncementEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        apply(entity, request, teacher)
        announcementRepository.save(entity)
        deliverIfDue(entity)
        return payload(entity)
    }

    @Transactional
    fun update(teacher: UserEntity, announcementId: String, request: TeacherAnnouncementPayload): TeacherAnnouncementPayload {
        val entity = ownEntity(teacher, announcementId) ?: throw notFound("Announcement not found")
        validate(request)
        apply(entity, request, teacher)
        announcementRepository.save(entity)
        deliverIfDue(entity)
        return payload(entity)
    }

    /** Repeat-safe: an unknown announcement is treated as already deleted. */
    @Transactional
    fun delete(teacher: UserEntity, announcementId: String) {
        val entity = ownEntity(teacher, announcementId) ?: return
        announcementRepository.delete(entity)
    }

    @Transactional(readOnly = true)
    fun analytics(teacher: UserEntity, announcementId: String): AnnouncementAnalyticsPayload {
        val entity = ownEntity(teacher, announcementId) ?: throw notFound("Announcement not found")
        val views = viewRepository.findAllByAnnouncementId(entity.id)
        val students = userRepository.findAllById(views.map { it.studentId }).associateBy { it.id }
        val statuses = views.mapNotNull { view ->
            val student = students[view.studentId] ?: return@mapNotNull null
            StudentAcknowledgementStatusPayload(
                studentId = view.studentId.toString(),
                studentName = student.name,
                viewedAt = view.viewedAt?.toEpochMilli(),
                acknowledgedAt = view.acknowledgedAt?.toEpochMilli(),
            )
        }
        return AnnouncementAnalyticsPayload(
            announcementId = entity.id.toString(),
            views = statuses.count { it.viewedAt != null },
            acknowledgements = statuses.count { it.acknowledgedAt != null },
            studentStatus = statuses,
        )
    }

    /** Delivers announcements whose scheduled instant has arrived (Phase 6 scheduler). */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    fun deliverDueAnnouncements() {
        announcementRepository.findAllByDeliveredAtIsNullAndScheduledAtLessThanEqual(clock.instant())
            .forEach { deliver(it) }
    }

    // ------------------------------------------------------------ internals

    private fun deliverIfDue(entity: TeacherAnnouncementEntity) {
        if (entity.deliveredAt != null) return
        val due = entity.scheduledAt == null || !entity.scheduledAt!!.isAfter(clock.instant())
        if (due) deliver(entity)
    }

    private fun deliver(entity: TeacherAnnouncementEntity) {
        val recipients = recipients(entity)
        val urgency = if (entity.isPriority) NotificationUrgency.HIGH else NotificationUrgency.NORMAL
        val priority = if (entity.isPriority) NotificationPriority.HIGH else NotificationPriority.NORMAL
        for (recipient in recipients) {
            notificationService.notifyUser(
                userId = recipient.id,
                title = entity.title,
                message = entity.content,
                type = NotificationType.ANNOUNCEMENT,
                urgency = urgency,
                priority = priority,
                actionRoute = "announcements/" + entity.clientId,
                actionLabel = "View",
                metadata = mapOf("announcementId" to entity.id.toString(), "type" to entity.announcementType),
            )
        }
        entity.deliveredAt = clock.instant()
        entity.updatedAt = clock.instant()
        announcementRepository.save(entity)
    }

    private fun recipients(entity: TeacherAnnouncementEntity): List<UserEntity> {
        val schoolId = entity.schoolId ?: return emptyList()
        val members = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
        if (entity.audience == "TEACHER") {
            return members.filter { it.role == Role.TEACHER && it.id != entity.teacherId }
        }
        val explicit = codec.parseList(entity.targetStudentIds).orEmpty()
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        val students = when {
            explicit.isNotEmpty() -> members.filter { it.role == Role.STUDENT && it.id in explicit }
            entity.audience == "SCHOOL" || entity.audience == "PARENT" -> members.filter { it.role == Role.STUDENT }
            entity.audience == "GRADE" -> {
                val grades = codec.parseList(entity.targetGradeLevels).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
                members.filter { it.role == Role.STUDENT && GradeNormalizer.canonicalKey(it.gradeLevel) in grades }
            }
            else -> {
                val classIds = codec.parseList(entity.targetClassIds).orEmpty()
                    .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
                val studentIds = classIds.flatMap { membershipRepository.findAllByClassId(it).map { m -> m.studentId } }.toSet()
                members.filter { it.role == Role.STUDENT && it.id in studentIds }
            }
        }
        if (entity.audience != "PARENT") return students
        val parentIds = students.mapNotNull { it.parentUserId }.toSet()
        return userRepository.findAllById(parentIds).filter { it.isActive }
    }

    private fun ownEntity(teacher: UserEntity, raw: String): TeacherAnnouncementEntity? {
        val entity = announcementRepository.findByClientId(raw)
            ?: runCatching { UUID.fromString(raw) }.getOrNull()?.let { announcementRepository.findById(it).orElse(null) }
            ?: return null
        if (entity.teacherId != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your announcement")
        return entity
    }

    private fun apply(entity: TeacherAnnouncementEntity, request: TeacherAnnouncementPayload, teacher: UserEntity) {
        entity.title = request.title.trim()
        entity.content = request.content.trim()
        entity.announcementType = enumValue(ANNOUNCEMENT_TYPES, request.type, "announcement type")
        entity.audience = enumValue(AUDIENCES, request.audience, "audience")
        entity.targetClassIds = codec.toJson(request.targetClassIds.filter { it.isNotBlank() })
        entity.targetGradeLevels = codec.toJson(request.targetGradeLevels.map { it.toString() })
        entity.targetStudentIds = codec.toJson(request.targetStudentIds.filter { it.isNotBlank() })
        entity.isPriority = request.isPriority
        entity.sentAt = if (request.sentAt > 0) Instant.ofEpochMilli(request.sentAt) else clock.instant()
        entity.scheduledAt = request.scheduledAt?.let(Instant::ofEpochMilli)
        entity.expiresAt = request.expiresAt?.let(Instant::ofEpochMilli)
        entity.schoolId = teacher.schoolId
    }

    private fun validate(request: TeacherAnnouncementPayload) {
        if (request.title.isBlank()) throw invalidArgument("Announcement title is required")
        if (request.content.isBlank()) throw invalidArgument("Announcement content is required")
        val scheduled = request.scheduledAt
        val expires = request.expiresAt
        if (scheduled != null && expires != null && expires <= scheduled) {
            throw invalidArgument("expiresAt must be after scheduledAt")
        }
    }

    private fun hidden(entity: TeacherAnnouncementEntity, now: Instant): Boolean =
        entity.expiresAt?.isBefore(now) == true

    private fun visibleTo(
        entity: TeacherAnnouncementEntity,
        user: UserEntity,
        subjectIds: Set<UUID>,
        classIds: Set<String>,
        grades: Set<Int>,
    ): Boolean {
        val explicitStudents = codec.parseList(entity.targetStudentIds).orEmpty()
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        if (explicitStudents.isNotEmpty()) return explicitStudents.any { it in subjectIds }
        return when (entity.audience) {
            "SCHOOL" -> true
            "PARENT" -> user.role == Role.PARENT
            "TEACHER" -> user.role == Role.TEACHER
            "GRADE" -> codec.parseList(entity.targetGradeLevels).orEmpty()
                .mapNotNull { it.toIntOrNull() }
                .any { it in grades }
            "CLASS" -> codec.parseList(entity.targetClassIds).orEmpty().any { it in classIds }
            else -> false
        }
    }

    private fun enumValue(allowed: Set<String>, raw: String, label: String): String {
        val upper = raw.trim().uppercase()
        if (upper in allowed) return upper
        throw invalidArgument("Unknown " + label + ": " + raw)
    }

    private fun payload(entity: TeacherAnnouncementEntity) = TeacherAnnouncementPayload(
        id = entity.clientId,
        teacherId = entity.teacherId.toString(),
        teacherName = userRepository.findById(entity.teacherId).orElse(null)?.name ?: "",
        title = entity.title,
        content = entity.content,
        type = entity.announcementType,
        audience = entity.audience,
        targetClassIds = codec.parseList(entity.targetClassIds) ?: emptyList(),
        targetGradeLevels = codec.parseList(entity.targetGradeLevels)?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
        targetStudentIds = codec.parseList(entity.targetStudentIds) ?: emptyList(),
        isPriority = entity.isPriority,
        sentAt = entity.sentAt.toEpochMilli(),
        viewCount = viewRepository.findAllByAnnouncementId(entity.id).count { it.viewedAt != null },
        acknowledgementCount = viewRepository.findAllByAnnouncementId(entity.id).count { it.acknowledgedAt != null },
        scheduledAt = entity.scheduledAt?.toEpochMilli(),
        expiresAt = entity.expiresAt?.toEpochMilli(),
    )

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    private companion object {
        val ANNOUNCEMENT_TYPES = setOf("NOTICE", "EVENT", "EXAM", "MEETING")
        val AUDIENCES = setOf("CLASS", "GRADE", "SCHOOL", "TEACHER", "PARENT")
    }
}
