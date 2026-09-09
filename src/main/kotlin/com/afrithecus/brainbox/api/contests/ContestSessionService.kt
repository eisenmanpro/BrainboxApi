package com.afrithecus.brainbox.api.contests

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contests.admin.ContestAuthoringService
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.entity.ContestSessionEntity
import com.afrithecus.brainbox.api.contests.entity.ContestSubmissionEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.model.ContestStatus
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSessionRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.contests.web.ContestResultPayload
import com.afrithecus.brainbox.api.contests.web.ContestSessionResponse
import com.afrithecus.brainbox.api.exams.AutoGrader
import com.afrithecus.brainbox.api.exams.model.SessionStatus
import com.afrithecus.brainbox.api.exams.web.QuestionResultPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Contest session lifecycle (doc 05 §1.5-§1.6): start/resume within the server
 * window, idempotent progress sync, submit with server-side grading and
 * server-computed integrity signals. Timer is driven by the contest end time.
 */
@Service
class ContestSessionService(
    private val contestRepository: ContestRepository,
    private val registrationRepository: ContestRegistrationRepository,
    private val sessionRepository: ContestSessionRepository,
    private val submissionRepository: ContestSubmissionRepository,
    private val authoring: ContestAuthoringService,
    private val autoGrader: AutoGrader,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional
    fun start(userId: UUID, contestIdRaw: String): ContestSessionResponse {
        val contest = activeContest(userId, contestIdRaw, requireRegistered = true)
        val now = clock.instant()
        val session = sessionRepository.findByUserIdAndContestId(userId, contest.id)
            ?: ContestSessionEntity().apply {
                this.contestId = contest.id
                this.userId = userId
                status = SessionStatus.IN_PROGRESS
                startedAt = now
                updatedAt = now
            }.also { sessionRepository.save(it) }
        val remaining = if (session.status == SessionStatus.IN_PROGRESS) {
            java.time.Duration.between(now, contest.endTime).seconds.coerceAtLeast(0).toInt()
        } else {
            0
        }
        return toSession(contest, session, remaining)
    }

    @Transactional
    fun sync(userId: UUID, contestIdRaw: String, body: JsonNode): Boolean {
        val contest = activeContest(userId, contestIdRaw, requireRegistered = true)
        val now = clock.instant()
        val session = sessionRepository.findByUserIdAndContestId(userId, contest.id)
            ?: ContestSessionEntity().apply {
                this.contestId = contest.id
                this.userId = userId
                status = SessionStatus.IN_PROGRESS
                startedAt = now
            }.also { sessionRepository.save(it) }
        if (session.status == SessionStatus.COMPLETED) return false
        body.get("answers")?.takeIf { it.isObject }?.let { session.answers = mapper.writeValueAsString(it) }
        body.get("currentQuestionIndex")?.takeIf { it.isNumber }?.let {
            session.currentIndex = it.intValue().coerceAtLeast(0)
        }
        session.updatedAt = now
        sessionRepository.save(session)
        return true
    }

    @Transactional
    fun submit(userId: UUID, contestIdRaw: String, answersBody: JsonNode): ContestResultPayload {
        if (!answersBody.isObject) throw invalidArgument("submit body must be a JSON object of answers")
        val contest = activeContest(userId, contestIdRaw, requireRegistered = true)
        val now = clock.instant()
        val session = sessionRepository.findByUserIdAndContestId(userId, contest.id)
            ?: throw ApiException(ApiErrorCode.CONFLICT, "No contest session in progress; start one first")
        if (session.status == SessionStatus.COMPLETED) throw conflict("Contest already submitted")

        val questions = authoring.questionsOf(contest.id)
        val totalPoints = questions.sumOf { it.points }
        var score = 0
        var correctCount = 0
        val results = questions.map { question ->
            val userValue = answersBody.get(question.id.toString())
            val outcome = autoGrader.grade(
                question.qType, question.correctAnswer, question.matchingPairs, question.points, userValue
            )
            score += outcome.pointsEarned
            if (outcome.isCorrect) correctCount++
            QuestionResultPayload(
                questionId = question.id.toString(),
                isCorrect = outcome.isCorrect,
                userAnswer = userValue?.let(::answerText),
                correctAnswer = question.correctAnswer,
                explanation = question.explanation,
                pointsEarned = outcome.pointsEarned,
            )
        }
        val percentage = if (totalPoints > 0) (score * 100.0 / totalPoints).roundToInt() else 0
        val timeTaken = java.time.Duration.between(session.startedAt, now).seconds.toInt().coerceAtLeast(0)
        val flags = mutableListOf<String>()
        if (timeTaken < minimumSecondsFor(questions.size)) flags.add("ABNORMALLY_FAST")
        val integrityScore = (100 - flags.size * INTEGRITY_FLAG_PENALTY).coerceAtLeast(0)

        val previous = submissionRepository.findByUserIdAndContestId(userId, contest.id)
        val submission = (previous ?: ContestSubmissionEntity().apply {
            this.contestId = contest.id
            this.userId = userId
        }).apply {
            this.score = score
            this.totalPoints = totalPoints
            this.percentage = percentage
            this.correctCount = correctCount
            this.questionCount = questions.size
            submittedAt = now
            answers = mapper.writeValueAsString(answersBody)
            questionResults = mapper.writeValueAsString(results)
        }
        submissionRepository.save(submission)

        session.status = SessionStatus.COMPLETED
        session.completedAt = now
        session.updatedAt = now
        session.answers = mapper.writeValueAsString(answersBody)
        sessionRepository.save(session)

        return ContestResultPayload(
            contestId = contest.id.toString(),
            userId = userId.toString(),
            score = score,
            totalPoints = totalPoints,
            percentage = percentage,
            correctAnswers = correctCount,
            totalQuestions = questions.size,
            timeTakenSeconds = timeTaken,
            submittedAt = now.toEpochMilli(),
            integrityScore = integrityScore,
            integrityFlags = flags,
            answers = answersBody,
            questionResults = results,
        )
    }

    // ------------------------------------------------------------ internals

    private fun activeContest(userId: UUID, raw: String, requireRegistered: Boolean): ContestEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("contest id is not a valid identifier")
        val contest = contestRepository.findById(id).orElse(null)
            ?: throw notFound("Contest not found")
        if (contest.lifecycle != ContestLifecycle.PUBLISHED) throw notFound("Contest not found")
        if (requireRegistered && registrationRepository.findByStudentIdAndContestId(userId, contest.id) == null) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Register for this contest before starting")
        }
        if (authoring.statusOf(contest) != ContestStatus.ONGOING) {
            throw conflict("Contest is not currently ongoing")
        }
        return contest
    }

    private fun toSession(contest: ContestEntity, session: ContestSessionEntity, remaining: Int): ContestSessionResponse {
        val questions = authoring.questionsOf(contest.id)
            .map { authoring.toQuestionPayload(it, includeKeys = false) }
        return ContestSessionResponse(
            id = session.id.toString(),
            contestId = contest.id.toString(),
            questions = questions,
            currentQuestionIndex = session.currentIndex,
            answers = session.answers?.let { runCatching { mapper.readTree(it) }.getOrNull() },
            startedAt = session.startedAt.toEpochMilli(),
            timeRemainingSeconds = remaining,
            status = session.status.name,
        )
    }

    private fun answerText(node: JsonNode): String = when {
        node.isValueNode -> node.asString()
        node.isArray -> (0 until node.size()).joinToString(", ") { node.get(it).asString() }
        node.isObject -> node.properties().joinToString(", ") { it.key + ": " + it.value.asString() }
        else -> node.toString()
    }

    private fun minimumSecondsFor(questionCount: Int): Int = max(10, questionCount * MIN_SECONDS_PER_QUESTION)

    private companion object {
        const val INTEGRITY_FLAG_PENALTY = 25
        const val MIN_SECONDS_PER_QUESTION = 3
    }
}
