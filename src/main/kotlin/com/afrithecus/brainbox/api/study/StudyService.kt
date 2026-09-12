package com.afrithecus.brainbox.api.study

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.study.entity.StudySessionEntity
import com.afrithecus.brainbox.api.study.repository.StudySessionRepository
import com.afrithecus.brainbox.api.study.web.RecordStudySessionRequest
import com.afrithecus.brainbox.api.study.web.StudyInsightsPayload
import com.afrithecus.brainbox.api.study.web.StudySessionPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Study tools (doc 03 §9). Sessions are server-owned and deduplicated on
 * (user, subject, topic, start) so replayed offline sync is idempotent; insights
 * are computed from the stored sessions, never trusted from the client.
 */
@Service
class StudyService(
    private val repository: StudySessionRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun sessions(current: CurrentUser, userIdRaw: String, limitRaw: Int): List<StudySessionPayload> {
        val userId = requireSelf(current, userIdRaw)
        return repository.findAllByUserIdOrderByStartTimeDesc(userId)
            .take(limitRaw.coerceIn(1, 500))
            .map(::payload)
    }

    @Transactional
    fun record(current: CurrentUser, request: RecordStudySessionRequest): StudySessionPayload {
        val userId = requireSelf(current, request.userId)
        val subject = request.subject.trim()
        if (subject.isEmpty()) throw invalidArgument("subject must not be blank")
        val topic = request.topic.trim()
        val start = Instant.ofEpochMilli(request.startTime)
        val end = Instant.ofEpochMilli(request.endTime)
        if (end.isBefore(start)) throw invalidArgument("endTime must not be before startTime")
        val duration = Duration.between(start, end).toMinutes().toInt()
        if (duration > MAX_SESSION_MINUTES) throw invalidArgument("Session duration exceeds the realistic maximum")

        // Idempotent replay: the same session re-synced returns the stored row.
        repository.findByUserIdAndSubjectAndTopicAndStartTime(userId, subject, topic, start)?.let { return payload(it) }

        val saved = repository.save(StudySessionEntity().apply {
            this.userId = userId
            this.subject = subject
            this.topic = topic
            this.startTime = start
            this.endTime = end
            durationMinutes = duration
            focusScore = request.focusScore
        })
        return payload(saved)
    }

    @Transactional(readOnly = true)
    fun insights(current: CurrentUser, userIdRaw: String): StudyInsightsPayload {
        val userId = requireSelf(current, userIdRaw)
        val sessions = repository.findAllByUserIdOrderByStartTimeDesc(userId)
        val totalMinutes = sessions.sumOf { it.durationMinutes }
        val mostStudied = sessions.groupBy { it.subject }
            .maxByOrNull { (_, list) -> list.sumOf { it.durationMinutes } }
            ?.key
            ?: ""
        val weekStart = clock.instant().minus(Duration.ofDays(7))
        val weeklyProgress = sessions.count { it.startTime.isAfter(weekStart) }
        val dates = sessions.map { LocalDate.ofInstant(it.startTime, clock.zone) }.toSet()
        return StudyInsightsPayload(
            totalStudyHours = round2(totalMinutes / 60.0),
            averageSessionDuration = if (sessions.isEmpty()) 0 else (totalMinutes.toDouble() / sessions.size).roundToInt(),
            mostStudiedSubject = mostStudied,
            streakDays = currentStreak(dates),
            weeklyGoal = WEEKLY_GOAL_SESSIONS,
            weeklyProgress = weeklyProgress,
            recommendations = recommendations(sessions, mostStudied, weeklyProgress),
        )
    }

    // ------------------------------------------------------------ internals

    private fun recommendations(sessions: List<StudySessionEntity>, mostStudied: String, weeklyProgress: Int): List<String> {
        if (sessions.isEmpty()) return listOf("Record your first study session to unlock personalised insights.")
        val out = mutableListOf<String>()
        val weakest = sessions.groupBy { it.subject }
            .entries
            .map { entry -> entry.key to entry.value.map { it.focusScore }.average() }
            .minByOrNull { it.second }
            ?.first
        if (weakest != null && weakest != mostStudied) {
            out += "Your focus dips in " + weakest + "; schedule a shorter, high-energy session there."
        }
        if (mostStudied.isNotEmpty()) out += "Keep your " + mostStudied + " momentum going."
        if (weeklyProgress < WEEKLY_GOAL_SESSIONS) {
            out += "You have " + weeklyProgress + " of " + WEEKLY_GOAL_SESSIONS + " sessions this week; aim for at least " + WEEKLY_GOAL_SESSIONS + "."
        }
        if (currentStreak(sessions.map { LocalDate.ofInstant(it.startTime, clock.zone) }.toSet()) == 0) {
            out += "Study today to start a new streak."
        }
        return out.take(4)
    }

    private fun payload(session: StudySessionEntity): StudySessionPayload = StudySessionPayload(
        id = session.id.toString(),
        userId = session.userId.toString(),
        subject = session.subject,
        topic = session.topic,
        startTime = session.startTime.toEpochMilli(),
        endTime = session.endTime.toEpochMilli(),
        durationMinutes = session.durationMinutes,
        focusScore = session.focusScore,
    )

    private fun currentStreak(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        val today = LocalDate.now(clock)
        var cursor = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (cursor in dates) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private fun requireSelf(current: CurrentUser, userIdRaw: String): UUID {
        if (userIdRaw != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's study data")
        }
        return current.userId
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private companion object {
        const val MAX_SESSION_MINUTES = 720
        const val WEEKLY_GOAL_SESSIONS = 7
    }
}
