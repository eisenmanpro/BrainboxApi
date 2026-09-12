package com.afrithecus.brainbox.api.live

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.entity.LiveClassEntity
import com.afrithecus.brainbox.api.live.entity.LiveClassMessageEntity
import com.afrithecus.brainbox.api.live.entity.LiveClassParticipantEntity
import com.afrithecus.brainbox.api.live.model.LiveClassStatus
import com.afrithecus.brainbox.api.live.repository.LiveAttendanceRepository
import com.afrithecus.brainbox.api.live.repository.LiveClassMessageRepository
import com.afrithecus.brainbox.api.live.repository.LiveClassParticipantRepository
import com.afrithecus.brainbox.api.live.repository.LiveClassRepository
import com.afrithecus.brainbox.api.live.repository.LivePollRepository
import com.afrithecus.brainbox.api.live.repository.LivePollVoteRepository
import com.afrithecus.brainbox.api.live.web.AttendanceDetailPayload
import com.afrithecus.brainbox.api.live.web.ChatMessagePayload
import com.afrithecus.brainbox.api.live.web.CreatePollRequest
import com.afrithecus.brainbox.api.live.web.LiveClassAnalyticsPayload
import com.afrithecus.brainbox.api.live.web.LiveClassParticipantPayload
import com.afrithecus.brainbox.api.live.web.LiveClassPayload
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.live.web.RecordedReplayPayload
import com.afrithecus.brainbox.api.live.web.TeacherLiveClassRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.math.round

/**
 * Teacher live-class hosting (docs/ongoing/api_live_class_changes.md): lifecycle,
 * recordings, analytics, the persisted host roster with replay-safe actions, the
 * chat transcript and host polls. Writes are idempotent on the client id because
 * the app replays them from its LiveSyncWorker outbox.
 */
@Service
class TeacherLiveClassService(
    private val classRepository: LiveClassRepository,
    private val participantRepository: LiveClassParticipantRepository,
    private val messageRepository: LiveClassMessageRepository,
    private val pollRepository: LivePollRepository,
    private val pollVoteRepository: LivePollVoteRepository,
    private val attendanceRepository: LiveAttendanceRepository,
    private val userRepository: UserRepository,
    private val liveClassService: LiveClassService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun teacherClasses(teacher: UserEntity, statusRaw: String?): List<LiveClassPayload> {
        requireTeacher(teacher)
        val status = statusRaw?.takeIf { it.isNotBlank() }?.let { parseStatus(it) }
        val rows = if (status == null) {
            classRepository.findAllByTeacherIdOrderByScheduledStartDesc(teacher.id)
        } else {
            classRepository.findAllByTeacherIdAndStatusOrderByScheduledStartDesc(teacher.id, status)
        }
        return rows.map { liveClassService.payload(it) }
    }

    /** Idempotent upsert keyed on the client-supplied class id. */
    @Transactional
    fun schedule(teacher: UserEntity, request: TeacherLiveClassRequest): LiveClassPayload {
        requireTeacher(teacher)
        if (request.title.isBlank()) throw invalidArgument("Live class title is required")
        if (request.subject.isBlank()) throw invalidArgument("Live class subject is required")
        if (request.scheduledEnd <= request.scheduledStart) {
            throw invalidArgument("scheduledEnd must be after scheduledStart")
        }
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "lc_" + UUID.randomUUID()
        val existing = classRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your live class")
        }
        val entity = existing ?: LiveClassEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        apply(entity, request, teacher)
        classRepository.saveAndFlush(entity)
        return liveClassService.payload(entity)
    }

    @Transactional
    fun update(teacher: UserEntity, classIdRaw: String, request: TeacherLiveClassRequest): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        apply(entity, request, teacher)
        classRepository.saveAndFlush(entity)
        return liveClassService.payload(entity)
    }

    /** Repeat-safe: an unknown class is treated as already cancelled. */
    @Transactional
    fun cancel(teacher: UserEntity, classIdRaw: String) {
        requireTeacher(teacher)
        val entity = resolveOwned(teacher, classIdRaw) ?: return
        classRepository.delete(entity)
    }

    @Transactional
    fun start(teacher: UserEntity, classIdRaw: String): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        entity.status = LiveClassStatus.LIVE
        classRepository.saveAndFlush(entity)
        return liveClassService.payload(entity)
    }

    @Transactional
    fun end(teacher: UserEntity, classIdRaw: String): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        entity.status = LiveClassStatus.COMPLETED
        classRepository.saveAndFlush(entity)
        return liveClassService.payload(entity)
    }

    @Transactional(readOnly = true)
    fun recordings(teacher: UserEntity): List<RecordedReplayPayload> {
        requireTeacher(teacher)
        return classRepository.findAllByTeacherIdAndStatusAndRecordingUrlIsNotNullOrderByScheduledStartDesc(
            teacher.id,
            LiveClassStatus.COMPLETED,
        ).map { clazz ->
            RecordedReplayPayload(
                id = clazz.id.toString(),
                title = clazz.title,
                subject = clazz.subject,
                views = "0 views",
                thumbnail = clazz.thumbnailUrl ?: "",
                videoUrl = clazz.recordingUrl,
                durationSeconds = Duration.between(clazz.scheduledStart, clazz.scheduledEnd).seconds.coerceAtLeast(0),
                createdAt = clazz.scheduledStart.toEpochMilli(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun participants(teacher: UserEntity, classIdRaw: String): List<LiveClassParticipantPayload> {
        requireTeacher(teacher)
        val clazz = requireOwned(teacher, classIdRaw)
        return participantRepository.findAllByClassIdAndIsRemovedFalseOrderByJoinTimeAsc(clazz.id)
            .map(::participantPayload)
    }

    /** Idempotent host action: re-applying MUTE/UNMUTE/KICK/PROMOTE converges. */
    @Transactional
    fun participantAction(teacher: UserEntity, classIdRaw: String, userIdRaw: String, actionRaw: String) {
        requireTeacher(teacher)
        val clazz = requireOwned(teacher, classIdRaw)
        val userId = parseUuid(userIdRaw, "user id")
        val action = actionRaw.trim().uppercase()
        if (action !in PARTICIPANT_ACTIONS) throw invalidArgument("Unknown participant action: " + actionRaw)
        val existing = participantRepository.findByClassIdAndUserId(clazz.id, userId)
        if (existing == null && action in setOf("REMOVE", "KICK")) return
        val row = existing ?: LiveClassParticipantEntity().apply {
            this.classId = clazz.id
            this.userId = userId
            userName = userRepository.findById(userId).orElse(null)?.name ?: "Participant"
            joinTime = clock.instant()
        }
        when (action) {
            "MUTE" -> row.isMuted = true
            "UNMUTE" -> row.isMuted = false
            "PROMOTE_TO_COHOST" -> row.role = "COHOST"
            "REMOVE", "KICK" -> row.isRemoved = true
        }
        participantRepository.saveAndFlush(row)
    }

    @Transactional(readOnly = true)
    fun messages(teacher: UserEntity, classIdRaw: String): List<ChatMessagePayload> {
        requireTeacher(teacher)
        val clazz = requireOwned(teacher, classIdRaw)
        return messageRepository.findAllByClassIdOrderBySentAtAsc(clazz.id).map(::messagePayload)
    }

    /** Learner/host chat send; idempotent per client message id. */
    @Transactional
    fun sendMessage(current: CurrentUser, classIdRaw: String, request: ChatMessagePayload): ChatMessagePayload {
        val clazz = requireClass(classIdRaw)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "msg_" + UUID.randomUUID()
        val existing = messageRepository.findByClientId(clientId)
        if (existing != null) return messagePayload(existing)
        val user = userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
        val entity = LiveClassMessageEntity().apply {
            this.clientId = clientId
            this.classId = clazz.id
            userId = user.id
            userName = user.name
            userRole = roleLabel(user.role)
            message = request.message.trim()
            sentAt = if (request.timestamp > 0) java.time.Instant.ofEpochMilli(request.timestamp) else clock.instant()
            isPinned = request.isPinned
        }
        if (entity.message.isEmpty()) throw invalidArgument("message is required")
        messageRepository.saveAndFlush(entity)
        return messagePayload(entity)
    }

    /** Host poll; reuses the existing poll store so learners vote on the same rows. */
    @Transactional
    fun sendPoll(current: CurrentUser, classIdRaw: String, request: LivePollPayload): LivePollPayload {
        val options = request.options.map { it.trim() }.filter { it.isNotEmpty() }
        return liveClassService.createPoll(current, classIdRaw, CreatePollRequest(request.question, options))
    }

    @Transactional(readOnly = true)
    fun analytics(teacher: UserEntity, classIdRaw: String): LiveClassAnalyticsPayload {
        requireTeacher(teacher)
        val clazz = requireOwned(teacher, classIdRaw)
        val participants = participantRepository.findAllByClassIdAndIsRemovedFalseOrderByJoinTimeAsc(clazz.id)
        val attendance = attendanceRepository.findAllByClassId(clazz.id)
        val messages = messageRepository.findAllByClassIdOrderBySentAtAsc(clazz.id)
        val polls = pollRepository.findAllByClassIdOrderByCreatedAtAsc(clazz.id)
        val votes = polls.sumOf { pollVoteRepository.findAllByPollId(it.id).size }
        val attendanceList = participants.map { participant ->
            val row = attendance.firstOrNull { it.studentId == participant.userId }
            val joinTime = row?.joinedAt ?: participant.joinTime
            AttendanceDetailPayload(
                userId = participant.userId.toString(),
                userName = participant.userName,
                role = participant.role,
                joinTime = joinTime.toEpochMilli(),
                leaveTime = row?.leftAt?.toEpochMilli(),
                durationMinutes = row?.durationMinutes ?: 0,
                leftEarly = row?.leftAt != null && row.leftAt!!.isBefore(clazz.scheduledEnd),
            )
        }
        val avgWatch = if (attendanceList.isEmpty()) 0.0 else attendanceList.map { it.durationMinutes }.average()
        return LiveClassAnalyticsPayload(
            classId = clazz.id.toString(),
            totalParticipants = participants.size,
            peakParticipants = participants.size,
            avgWatchTimeMinutes = round2(avgWatch),
            totalChatMessages = messages.size,
            totalPollResponses = votes,
            totalQuestionsAsked = polls.size,
            attendanceList = attendanceList,
        )
    }

    // ------------------------------------------------------------ internals

    internal fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    private fun apply(entity: LiveClassEntity, request: TeacherLiveClassRequest, teacher: UserEntity) {
        entity.teacherId = teacher.id
        entity.teacherName = teacher.name
        entity.schoolId = teacher.schoolId
        entity.title = request.title.trim()
        entity.subject = request.subject.trim()
        entity.description = request.description.trim()
        entity.scheduledStart = java.time.Instant.ofEpochMilli(request.scheduledStart)
        entity.scheduledEnd = java.time.Instant.ofEpochMilli(request.scheduledEnd)
        if (request.status.isNotBlank() && entity.status.name != request.status.trim().uppercase()) {
            entity.status = runCatching { LiveClassStatus.valueOf(request.status.trim().uppercase()) }.getOrNull()
                ?: entity.status
        }
        entity.visibility = request.settings.visibility.trim().uppercase().ifEmpty { "CLASS_ONLY" }
        entity.autoRecord = request.settings.autoRecord
        entity.muteOnJoin = request.settings.muteOnJoin
        entity.waitingRoom = request.settings.waitingRoom
        entity.allowChat = request.settings.allowChat
        entity.allowQandA = request.settings.allowQandA
        entity.participantIds = mapper.writeValueAsString(request.participantIds.filter { it.isNotBlank() })
        entity.materialIds = mapper.writeValueAsString(request.materialIds.filter { it.isNotBlank() })
        request.recordingUrl?.let { entity.recordingUrl = it }
        request.analyticsId?.let { entity.analyticsId = it }
    }

    private fun resolveOwned(teacher: UserEntity, raw: String): LiveClassEntity? {
        val byClient = classRepository.findByClientId(raw)
        if (byClient != null) {
            if (byClient.teacherId != teacher.id) throw forbidden()
            return byClient
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = classRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden()
        return entity
    }

    private fun requireOwned(teacher: UserEntity, raw: String): LiveClassEntity =
        resolveOwned(teacher, raw) ?: throw notFound("Live class not found")

    private fun requireClass(raw: String): LiveClassEntity =
        classRepository.findById(parseUuid(raw, "class id")).orElse(null) ?: throw notFound("Live class not found")

    private fun participantPayload(entity: LiveClassParticipantEntity) = LiveClassParticipantPayload(
        classId = entity.classId.toString(),
        userId = entity.userId.toString(),
        userName = entity.userName,
        role = entity.role,
        isMuted = entity.isMuted,
        joinTime = entity.joinTime.toEpochMilli(),
    )

    private fun messagePayload(entity: LiveClassMessageEntity) = ChatMessagePayload(
        id = entity.clientId,
        classId = entity.classId.toString(),
        userId = entity.userId.toString(),
        userName = entity.userName,
        userRole = entity.userRole,
        message = entity.message,
        timestamp = entity.sentAt.toEpochMilli(),
        isPinned = entity.isPinned,
    )

    private fun roleLabel(role: Role): String = when (role) {
        Role.TEACHER, Role.ADMIN -> "TEACHER"
        Role.STUDENT -> "STUDENT"
        Role.PARENT -> "SUPPORT"
    }

    private fun parseStatus(raw: String): LiveClassStatus =
        runCatching { LiveClassStatus.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown live class status: " + raw)

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(label + " is not a valid identifier")

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    private fun forbidden() = ApiException(ApiErrorCode.FORBIDDEN, "Not your live class")

    private companion object {
        val PARTICIPANT_ACTIONS = setOf("MUTE", "UNMUTE", "REMOVE", "KICK", "PROMOTE_TO_COHOST")
    }
}
