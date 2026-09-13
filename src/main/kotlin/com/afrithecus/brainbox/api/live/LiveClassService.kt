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
import com.afrithecus.brainbox.api.live.entity.LiveAttendanceEntity
import com.afrithecus.brainbox.api.live.entity.LiveClassEntity
import com.afrithecus.brainbox.api.live.entity.LivePollEntity
import com.afrithecus.brainbox.api.live.entity.LivePollVoteEntity
import com.afrithecus.brainbox.api.live.entity.LiveRegistrationEntity
import com.afrithecus.brainbox.api.live.model.LiveClassStatus
import com.afrithecus.brainbox.api.live.repository.LiveAttendanceRepository
import com.afrithecus.brainbox.api.live.repository.LiveClassRepository
import com.afrithecus.brainbox.api.live.repository.LivePollRepository
import com.afrithecus.brainbox.api.live.repository.LivePollVoteRepository
import com.afrithecus.brainbox.api.live.repository.LiveRegistrationRepository
import com.afrithecus.brainbox.api.live.web.AttendanceRequest
import com.afrithecus.brainbox.api.live.web.CreateLiveClassRequest
import com.afrithecus.brainbox.api.live.web.CreatePollRequest
import com.afrithecus.brainbox.api.live.web.LiveClassPayload
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.live.web.MaterialPayload
import com.afrithecus.brainbox.api.live.web.RecordedReplayPayload
import com.afrithecus.brainbox.api.live.web.TeacherSpotlightPayload
import com.afrithecus.brainbox.api.live.web.UpdateLiveClassStatusRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Live classes (doc 05 §4). Capacity, registration, attendance and poll voting
 * are enforced server-side; the client renders the returned live state.
 */
@Service
class LiveClassService(
    private val classRepository: LiveClassRepository,
    private val registrationRepository: LiveRegistrationRepository,
    private val attendanceRepository: LiveAttendanceRepository,
    private val pollRepository: LivePollRepository,
    private val pollVoteRepository: LivePollVoteRepository,
    private val userRepository: UserRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun upcoming(): List<LiveClassPayload> =
        classRepository.findAllByStatusOrderByScheduledStartAsc(LiveClassStatus.SCHEDULED).map { payload(it, includeScheduleLabels = true) }

    @Transactional(readOnly = true)
    fun ongoing(): List<LiveClassPayload> =
        classRepository.findAllByStatusOrderByScheduledStartAsc(LiveClassStatus.LIVE).map { payload(it) }

    @Transactional(readOnly = true)
    fun completed(): List<LiveClassPayload> =
        classRepository.findAllByStatusOrderByScheduledStartAsc(LiveClassStatus.COMPLETED).map { payload(it) }

    @Transactional(readOnly = true)
    fun replays(): List<RecordedReplayPayload> =
        classRepository.findAllByStatusAndRecordingUrlIsNotNullOrderByScheduledStartDesc(LiveClassStatus.COMPLETED)
            .map { clazz ->
                val views = registrationRepository.countByClassId(clazz.id)
                RecordedReplayPayload(
                    id = clazz.id.toString(),
                    title = clazz.title,
                    subject = clazz.subject,
                    views = formatViews(views),
                    thumbnail = clazz.thumbnailUrl ?: "",
                    videoUrl = clazz.recordingUrl,
                    durationSeconds = Duration.between(clazz.scheduledStart, clazz.scheduledEnd).seconds.coerceAtLeast(0),
                    createdAt = clazz.scheduledStart.toEpochMilli(),
                )
            }

    @Transactional(readOnly = true)
    fun spotlight(): TeacherSpotlightPayload {
        val classes = classRepository.findAllByOrderByScheduledStartDesc()
        if (classes.isEmpty()) {
            return TeacherSpotlightPayload("", "", "", emptyList(), 0f, 0, 0, "")
        }
        val top = classes.groupBy { it.teacherId }.maxByOrNull { it.value.size }!!.value
        val teacher = userRepository.findById(top.first().teacherId).orElse(null)
        val students = top.sumOf { registrationRepository.countByClassId(it.id) }.toInt()
        val fill = top.map { clazz ->
            if (clazz.maxParticipants <= 0) 0.0
            else registrationRepository.countByClassId(clazz.id).toDouble() / clazz.maxParticipants
        }.average()
        val rating = ((fill * 5.0).coerceIn(0.0, 5.0) * 10).roundToInt() / 10.0
        return TeacherSpotlightPayload(
            id = top.first().teacherId.toString(),
            name = top.first().teacherName.ifBlank { teacher?.name ?: "" },
            photo = "",
            subjects = top.map { it.subject }.distinct(),
            rating = rating.toFloat(),
            studentCount = students,
            classCount = top.size,
            successStory = "Runs " + top.size + " live classes on " + top.map { it.subject }.distinct().joinToString(", "),
        )
    }

    @Transactional(readOnly = true)
    fun detail(classIdRaw: String): LiveClassPayload =
        payload(requireClass(classIdRaw), includeScheduleLabels = true)

    @Transactional
    fun register(current: CurrentUser, classIdRaw: String): Map<String, String> {
        val clazz = requireClass(classIdRaw)
        if (clazz.status == LiveClassStatus.CANCELLED) throw conflict("Class was cancelled")
        if (registrationRepository.findByClassIdAndStudentId(clazz.id, current.userId) != null) {
            return mapOf("status" to "success", "message" to "Already registered")
        }
        val registered = registrationRepository.countByClassId(clazz.id)
        if (registered >= clazz.maxParticipants) throw conflict("Class is full")
        registrationRepository.save(LiveRegistrationEntity().apply {
            this.classId = clazz.id
            this.studentId = current.userId
        })
        return mapOf("status" to "success", "message" to "Registered for " + clazz.title)
    }

    @Transactional
    fun recordAttendance(current: CurrentUser, classIdRaw: String, request: AttendanceRequest): Map<String, String> {
        val clazz = requireClass(classIdRaw)
        val targetId = request.userId?.let { parseUuid(it, "user id") } ?: current.userId
        if (targetId != current.userId) {
            requireHost(current, clazz)
        }
        val requestedStatus = (request.status ?: if (request.isPresent == false) "ABSENT" else "PRESENT").trim().uppercase()
        if (requestedStatus !in setOf("PRESENT", "ABSENT", "LATE")) {
            throw invalidArgument("Unknown attendance status: " + requestedStatus)
        }
        val joinedAt = request.checkInTime?.let(Instant::ofEpochMilli)
        val leftAt = request.leaveTime?.let(Instant::ofEpochMilli)
        val knownDuration = when {
            joinedAt != null && leftAt != null ->
                Duration.between(joinedAt, leftAt).toMinutes().coerceAtLeast(0L).toInt()
            request.durationMinutes != null -> request.durationMinutes!!.coerceAtLeast(0)
            else -> null
        }
        // A learner who joins and leaves inside MIN_ATTENDED_MINUTES never reads as
        // present, even when an offline replay omits isPresent/durationMinutes
        // (mirrors the client's MIN_ATTENDED_MINUTES gate).
        val leftTooEarly = leftAt != null && knownDuration != null && knownDuration < MIN_ATTENDED_MINUTES
        val status = if (leftTooEarly) "ABSENT" else requestedStatus
        val row = attendanceRepository.findByClassIdAndStudentId(clazz.id, targetId) ?: LiveAttendanceEntity().apply {
            this.classId = clazz.id
            this.studentId = targetId
        }
        row.status = status
        row.joinedAt = joinedAt
        row.leftAt = leftAt
        row.durationMinutes = knownDuration ?: 0
        row.isPresent = if (leftTooEarly) false else (request.isPresent ?: (status != "ABSENT"))
        row.reason = request.reason
        row.recordedAt = clock.instant()
        attendanceRepository.save(row)
        return mapOf("status" to "success", "message" to "Attendance recorded")
    }

    @Transactional(readOnly = true)
    fun polls(classIdRaw: String): List<LivePollPayload> =
        pollRepository.findAllByClassIdOrderByCreatedAtAsc(requireClass(classIdRaw).id).map(::pollPayload)

    @Transactional
    fun createPoll(current: CurrentUser, classIdRaw: String, request: CreatePollRequest): LivePollPayload {
        val clazz = requireClass(classIdRaw)
        requireHost(current, clazz)
        val options = request.options.map { it.trim() }.filter { it.isNotEmpty() }
        if (options.size < 2) throw invalidArgument("A poll needs at least two options")
        val poll = pollRepository.save(LivePollEntity().apply {
            this.classId = clazz.id
            this.createdBy = current.userId
            this.question = request.question.trim()
            this.options = mapper.writeValueAsString(options)
        })
        return pollPayload(poll)
    }

    @Transactional
    fun vote(current: CurrentUser, classIdRaw: String, pollIdRaw: String, optionIndex: Int): LivePollPayload {
        val clazz = requireClass(classIdRaw)
        val poll = pollRepository.findById(parseUuid(pollIdRaw, "poll id")).orElse(null) ?: throw notFound("Poll not found")
        if (poll.classId != clazz.id) throw notFound("Poll not found")
        if (!poll.isActive) throw conflict("Poll is closed")
        val options = parseList(poll.options)
        if (optionIndex < 0 || optionIndex >= options.size) throw invalidArgument("optionIndex is out of range")
        val existing = pollVoteRepository.findByPollIdAndUserId(poll.id, current.userId)
        if (existing == null) {
            pollVoteRepository.save(LivePollVoteEntity().apply {
                this.pollId = poll.id
                this.userId = current.userId
                this.optionIndex = optionIndex
            })
        } else {
            existing.optionIndex = optionIndex
            existing.votedAt = clock.instant()
            pollVoteRepository.save(existing)
        }
        return pollPayload(poll)
    }

    @Transactional
    fun create(current: CurrentUser, request: CreateLiveClassRequest): LiveClassPayload {
        if (request.scheduledEnd <= request.scheduledStart) throw invalidArgument("scheduledEnd must be after scheduledStart")
        val teacher = userRepository.findById(parseUuid(request.teacherId, "teacher id")).orElse(null)
            ?: throw notFound("Teacher not found")
        val clazz = classRepository.save(LiveClassEntity().apply {
            teacherId = teacher.id
            teacherName = teacher.name
            schoolId = teacher.schoolId
            title = request.title.trim()
            subject = request.subject.trim()
            description = request.description?.trim() ?: ""
            scheduledStart = Instant.ofEpochMilli(request.scheduledStart)
            scheduledEnd = Instant.ofEpochMilli(request.scheduledEnd)
            status = LiveClassStatus.SCHEDULED
            maxParticipants = request.maxParticipants
            joinUrl = request.joinUrl
            thumbnailUrl = request.thumbnailUrl
            materials = mapper.writeValueAsString(request.materials)
        })
        return payload(clazz)
    }

    @Transactional
    fun listAll(): List<LiveClassPayload> =
        classRepository.findAllByOrderByScheduledStartDesc().map { payload(it) }

    @Transactional
    fun updateStatus(classIdRaw: String, request: UpdateLiveClassStatusRequest): LiveClassPayload {
        val clazz = requireClass(classIdRaw)
        clazz.status = runCatching { LiveClassStatus.valueOf(request.status.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown live class status: " + request.status)
        request.joinUrl?.let { clazz.joinUrl = it }
        request.recordingUrl?.let { clazz.recordingUrl = it }
        return payload(classRepository.save(clazz))
    }

    // ------------------------------------------------------------ internals

    internal fun payload(clazz: LiveClassEntity, includeScheduleLabels: Boolean = false): LiveClassPayload {
        val registered = registrationRepository.countByClassId(clazz.id).toInt()
        val zoned = ZonedDateTime.ofInstant(clazz.scheduledStart, clock.zone)
        return LiveClassPayload(
            id = clazz.id.toString(),
            title = clazz.title,
            teacherId = clazz.teacherId.toString(),
            teacherName = clazz.teacherName,
            subject = clazz.subject,
            description = clazz.description,
            scheduledStart = clazz.scheduledStart.toEpochMilli(),
            scheduledEnd = clazz.scheduledEnd.toEpochMilli(),
            status = clazz.status.name,
            joinUrl = clazz.joinUrl,
            recordingUrl = clazz.recordingUrl,
            participantCount = registered,
            maxParticipants = clazz.maxParticipants,
            thumbnailUrl = clazz.thumbnailUrl,
            materials = parseMaterials(clazz.materials),
            day = if (includeScheduleLabels) dayLabel(zoned) else null,
            time = if (includeScheduleLabels) TIME_FORMAT.format(zoned) else null,
            scheduledStartMillis = clazz.scheduledStart.toEpochMilli(),
        )
    }

    private fun pollPayload(poll: LivePollEntity): LivePollPayload {
        val options = parseList(poll.options)
        val votes = pollVoteRepository.findAllByPollId(poll.id)
        val counts = votes.groupingBy { it.optionIndex }.eachCount()
        val voteMap = LinkedHashMap<Int, Int>()
        options.indices.forEach { voteMap[it] = counts[it] ?: 0 }
        val resultMap = LinkedHashMap<String, Int>()
        options.forEachIndexed { index, option -> resultMap[option] = counts[index] ?: 0 }
        return LivePollPayload(
            id = poll.id.toString(),
            classId = poll.classId.toString(),
            question = poll.question,
            options = options,
            votes = voteMap,
            results = resultMap,
            isActive = poll.isActive,
            createdAt = poll.createdAt.toEpochMilli(),
        )
    }

    private fun dayLabel(zoned: ZonedDateTime): String {
        val date = zoned.toLocalDate()
        val today = LocalDate.now(clock)
        return when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> DATE_FORMAT.format(zoned)
        }
    }

    private fun formatViews(views: Long): String =
        if (views >= 1000) String.format(Locale.US, "%.1fK views", views / 1000.0) else views.toString() + " views"

    private fun requireClass(classIdRaw: String): LiveClassEntity =
        classRepository.findById(parseUuid(classIdRaw, "class id")).orElse(null) ?: throw notFound("Live class not found")

    private fun requireHost(current: CurrentUser, clazz: LiveClassEntity) {
        if (clazz.teacherId == current.userId) return
        val user = userRepository.findById(current.userId).orElse(null)
        if (user?.role == Role.ADMIN) return
        throw ApiException(ApiErrorCode.FORBIDDEN, "Only the class teacher can do this")
    }

    private fun parseMaterials(json: String?): List<MaterialPayload> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).mapNotNull { index ->
            val item = node.get(index)
            val name = item.get("name")?.asString() ?: return@mapNotNull null
            val url = item.get("url")?.asString() ?: return@mapNotNull null
            MaterialPayload(name, url)
        }
    }

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private companion object {
        const val MIN_ATTENDED_MINUTES = 5
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US)
    }
}
