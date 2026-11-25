package com.afrithecus.brainbox.api.live

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
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
import com.afrithecus.brainbox.api.live.ws.LiveSignalingHandler
import com.afrithecus.brainbox.api.live.web.AttendanceDetailPayload
import com.afrithecus.brainbox.api.live.web.ChatMessagePayload
import com.afrithecus.brainbox.api.live.web.CreatePollRequest
import com.afrithecus.brainbox.api.live.web.LiveClassAnalyticsPayload
import com.afrithecus.brainbox.api.live.web.LiveClassParticipantPayload
import com.afrithecus.brainbox.api.live.web.LiveClassPayload
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.live.web.LiveRecordingUploadRequest
import com.afrithecus.brainbox.api.live.web.RecordedReplayPayload
import com.afrithecus.brainbox.api.live.web.TeacherLiveClassRequest
import com.afrithecus.brainbox.api.media.MediaUploadService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
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
    private val teacherClassRepository: com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository,
    private val participantRepository: LiveClassParticipantRepository,
    private val messageRepository: LiveClassMessageRepository,
    private val pollRepository: LivePollRepository,
    private val pollVoteRepository: LivePollVoteRepository,
    private val attendanceRepository: LiveAttendanceRepository,
    private val userRepository: UserRepository,
    private val liveClassService: LiveClassService,
    private val signaling: LiveSignalingHandler,
    /** Verifies a client-direct recording upload before its URL is attached. */
    private val mediaUploadService: MediaUploadService,
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
        signaling.broadcastClassEnded(entity.id, "Class cancelled")
    }

    /**
     * Starts a session. Repeat-safe: starting an already-LIVE class keeps its original
     * startedAt (so a refreshed host resumes the same clock) and refuses a COMPLETED or
     * CANCELLED class, which is what stops a stale LIVE row from being re-started as a
     * fresh broadcast. The status is lifecycle-owned: a schedule/update payload can
     * never move it.
     */
    @Transactional
    fun start(teacher: UserEntity, classIdRaw: String): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        when (entity.status) {
            LiveClassStatus.LIVE -> {
                if (entity.startedAt == null) entity.startedAt = clock.instant()
                entity.lastHostSeenAt = clock.instant()
                classRepository.saveAndFlush(entity)
                return liveClassService.payload(entity)
            }
            LiveClassStatus.COMPLETED, LiveClassStatus.CANCELLED ->
                throw conflict("This live class has already ended")
            LiveClassStatus.SCHEDULED -> Unit
        }
        val now = clock.instant()
        entity.status = LiveClassStatus.LIVE
        entity.startedAt = now
        entity.endedAt = null
        entity.lastHostSeenAt = now
        classRepository.saveAndFlush(entity)
        return liveClassService.payload(entity)
    }

    /** Ends a session. Repeat-safe: ending a COMPLETED class returns it unchanged. */
    @Transactional
    fun end(teacher: UserEntity, classIdRaw: String): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        if (entity.status == LiveClassStatus.COMPLETED) return liveClassService.payload(entity)
        if (entity.status == LiveClassStatus.CANCELLED) throw conflict("This live class was cancelled")
        val now = clock.instant()
        entity.status = LiveClassStatus.COMPLETED
        if (entity.endedAt == null) entity.endedAt = now
        entity.lastHostSeenAt = now
        classRepository.saveAndFlush(entity)
        // Tell any connected signaling peers the session is over.
        signaling.broadcastClassEnded(entity.id, "Host ended the class")
        return liveClassService.payload(entity)
    }

    /**
     * Host liveness ping while LIVE. The stored timestamp is what distinguishes an
     * active broadcast from a stale LIVE row; a heartbeat for a class that is no
     * longer LIVE is refused so a long-lived tab cannot resurrect it.
     */
    @Transactional
    fun heartbeat(teacher: UserEntity, classIdRaw: String): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        if (entity.status != LiveClassStatus.LIVE) throw conflict("This live class is not live")
        entity.lastHostSeenAt = clock.instant()
        classRepository.saveAndFlush(entity)
        return liveClassService.payload(entity)
    }

    /**
     * Closes LIVE classes whose scheduled window plus grace has passed. Without this a
     * broadcast whose host vanished stays LIVE forever, blocking re-scheduling and
     * showing learners a dead session. Called by LiveClassExpiryScheduler.
     */
    @Transactional
    fun expireStaleSessions(): Int {
        val now = clock.instant()
        val stale = classRepository.findAllByStatusOrderByScheduledStartAsc(LiveClassStatus.LIVE)
            .filter { it.scheduledEnd.plus(STALE_GRACE).isBefore(now) }
        stale.forEach { entity ->
            entity.status = LiveClassStatus.COMPLETED
            entity.endedAt = entity.endedAt ?: now
            classRepository.save(entity)
            signaling.broadcastClassEnded(entity.id, "Session timed out")
        }
        return stale.size
    }

    /**
     * Attaches the recording the host finished. Two real paths:
     *  - uploadId: a confirmed client-direct media ticket owned by this teacher. The
     *    server verifies its kind is video before storing the served URL, so a row can
     *    never point at bytes that were never uploaded.
     *  - recordingUrl: an externally hosted https link or a server-served /media path.
     *    Scheme-checked because the URL is rendered back to learners.
     * A null/blank both is refused. Duration is the client's measured length.
     */
    @Transactional
    fun attachRecording(
        teacher: UserEntity,
        classIdRaw: String,
        request: LiveRecordingUploadRequest,
    ): LiveClassPayload {
        requireTeacher(teacher)
        val entity = requireOwned(teacher, classIdRaw)
        if (entity.status != LiveClassStatus.COMPLETED && entity.status != LiveClassStatus.LIVE) {
            throw conflict("Record a live or completed session")
        }
        val duration = request.durationSeconds?.takeIf { it > 0 }
        val uploadId = request.uploadId?.trim()?.takeIf { it.isNotEmpty() }
        if (uploadId != null) {
            val confirmed = mediaUploadService.confirmedUpload(teacher, uploadId)
                ?: throw invalidArgument("The recording upload has not been confirmed yet")
            if (!confirmed.kind.isVideo) throw invalidArgument("The uploaded file is not a video")
            entity.recordingUrl = confirmed.url
            entity.recordingDurationSeconds = duration
        } else {
            val url = request.recordingUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw invalidArgument("uploadId or recordingUrl is required")
            if (!url.startsWith("https://") && !url.startsWith("/media/")) {
                throw invalidArgument("recordingUrl must be an https link or a /media path")
            }
            entity.recordingUrl = url
            entity.recordingDurationSeconds = duration
        }
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
                views = "0 views", // no view-tracking store exists; a fake count would be fabricated
                thumbnail = clazz.thumbnailUrl ?: "",
                videoUrl = clazz.recordingUrl,
                durationSeconds = clazz.recordingDurationSeconds?.toLong()
                    ?: Duration.between(clazz.scheduledStart, clazz.scheduledEnd).seconds.coerceAtLeast(0),
                createdAt = (clazz.startedAt ?: clazz.scheduledStart).toEpochMilli(),
            )
        }
    }

    // ------------------------------------------------------------ participants
    /**
     * Host roster plus real socket presence. Persisted rows carry mute/promote/kick
     * state; a participant who is connected through the signaling channel but has not
     * had an action applied yet is merged in from the live socket registry (never
     * invented from the class roster). With neither source the feed is honestly empty.
     */
    @Transactional(readOnly = true)
    fun participants(teacher: UserEntity, classIdRaw: String): List<LiveClassParticipantPayload> {
        requireTeacher(teacher)
        val clazz = requireOwned(teacher, classIdRaw)
        val rows = participantRepository.findAllByClassIdAndIsRemovedFalseOrderByJoinTimeAsc(clazz.id)
            .associateBy { it.userId }
            .toMutableMap()
        signaling.presentUserIds(clazz.id).forEach { userId ->
            if (rows.containsKey(userId)) return@forEach
            val removed = participantRepository.findByClassIdAndUserId(clazz.id, userId)
            if (removed?.isRemoved == true) return@forEach
            val user = userRepository.findById(userId).orElse(null) ?: return@forEach
            rows[userId] = LiveClassParticipantEntity().apply {
                this.classId = clazz.id
                this.userId = userId
                userName = user.name
                role = if (user.role == Role.TEACHER) "COHOST" else "STUDENT"
                joinTime = clock.instant()
                isRemoved = false
            }
        }
        return rows.values.sortedBy { it.joinTime }.map(::participantPayload)
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
    fun sendPoll(
        current: CurrentUser,
        classIdRaw: String,
        request: LivePollPayload,
        clientPollId: String? = null,
    ): LivePollPayload {
        val options = request.options.map { it.trim() }.filter { it.isNotEmpty() }
        // The client's poll id doubles as the idempotency key for a queued send; a body
        // that carries the id but no query parameter is honoured too.
        val stableId = clientPollId?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.id.takeIf { it.isNotBlank() }
        return liveClassService.createPoll(
            current,
            classIdRaw,
            CreatePollRequest(request.question, options),
            stableId,
        )
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
        val reference = clazz.startedAt ?: clazz.scheduledStart
        // Engagement timeline: real chat activity bucketed by minute from the actual start,
        // keyed by epoch ms so the client can label each bar with a clock time.
        val timeline = messages
            .groupingBy { message ->
                val minute = Duration.between(reference, message.sentAt).toMinutes().coerceAtLeast(0L)
                reference.plusSeconds(minute * 60).toEpochMilli()
            }
            .eachCount()
            .toSortedMap()
        // Drop-off: learners remaining at each minute where somebody actually left.
        val leaveMinutes = attendance
            .mapNotNull { row -> row.leftAt?.let { Duration.between(reference, it).toMinutes().coerceAtLeast(0L).toInt() } }
            .sorted()
        val totalAudience = maxOf(participants.size, attendance.size)
        val dropOff = LinkedHashMap<Int, Int>()
        var remaining = totalAudience
        leaveMinutes.forEach { minute ->
            remaining = (remaining - 1).coerceAtLeast(0)
            dropOff[minute] = remaining
        }
        // Peak concurrency from real join/leave events (a join at the same instant as a
        // leave is counted first, so a hand-off never reads as two people).
        val events = ArrayList<Pair<Instant, Int>>()
        attendanceList.forEach { row ->
            events.add(Instant.ofEpochMilli(row.joinTime) to 1)
            row.leaveTime?.let { events.add(Instant.ofEpochMilli(it) to -1) }
        }
        events.sortWith(compareBy<Pair<Instant, Int>>({ it.first }, { -it.second }))
        var concurrent = 0
        var peak = 0
        events.forEach { event ->
            concurrent = (concurrent + event.second).coerceAtLeast(0)
            if (concurrent > peak) peak = concurrent
        }
        return LiveClassAnalyticsPayload(
            classId = clazz.id.toString(),
            totalParticipants = totalAudience,
            peakParticipants = maxOf(peak, if (totalAudience > 0 && events.isEmpty()) totalAudience else 0),
            avgWatchTimeMinutes = round2(avgWatch),
            totalChatMessages = messages.size,
            totalPollResponses = votes,
            totalQuestionsAsked = polls.size,
            attendanceList = attendanceList,
            engagementTimeline = timeline,
            dropOffPoints = dropOff,
            // Ratings and written feedback have no collection surface yet; 0/empty is the
            // honest value rather than a synthesised score.
            averageRating = 0f,
            feedbackComments = emptyList(),
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
        // A blank/absent classId keeps the existing link: Android's model has no such
        // field, so one of its edits must not silently unlink a web-authored session.
        request.classId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            entity.schoolClassId = ownedSchoolClass(teacher, raw).id
        }
        entity.title = request.title.trim()
        entity.subject = request.subject.trim()
        entity.description = request.description.trim()
        entity.scheduledStart = java.time.Instant.ofEpochMilli(request.scheduledStart)
        entity.scheduledEnd = java.time.Instant.ofEpochMilli(request.scheduledEnd)
        // Status is lifecycle-owned (start/end only). Accepting it here let an edit or an
        // offline replay move a COMPLETED session back to LIVE (and vice versa), which is
        // exactly the stale-LIVE bug this pass fixes. New rows keep the SCHEDULED default.
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

    /**
     * Resolves the school class a session targets and refuses one in another tenant. The
     * link is web-only (Android has no field for it), so it stays optional but, once sent,
     * it must be a real class of the caller's school.
     */
    private fun ownedSchoolClass(teacher: UserEntity, raw: String): com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity {
        val id = parseUuid(raw, "classId")
        val clazz = teacherClassRepository.findById(id).orElse(null) ?: throw notFound("Class not found")
        if (clazz.schoolId != teacher.schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "That class is not in your school")
        }
        return clazz
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

        /** A LIVE row past its scheduled end by this much is closed as timed out. */
        val STALE_GRACE: Duration = Duration.ofHours(2)
    }
}
