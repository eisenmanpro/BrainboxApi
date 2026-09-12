package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.admin.ExamAuthoringService
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSessionEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.SessionStatus
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSessionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.ExamResultPayload
import com.afrithecus.brainbox.api.exams.web.ExamSessionResponse
import com.afrithecus.brainbox.api.exams.web.ExamSubmissionDetailsPayload
import com.afrithecus.brainbox.api.exams.web.QuestionResultPayload
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Exam session lifecycle (doc 02 §3): start/resume, idempotent progress sync,
 * submit with server-side auto-grading, and results. Student payloads never
 * receive key material; grading happens entirely server-side from raw answers.
 */
@Service
class ExamSessionService(
    private val examRepository: ExamRepository,
    private val sessionRepository: ExamSessionRepository,
    private val submissionRepository: ExamSubmissionRepository,
    private val authoringService: ExamAuthoringService,
    private val autoGrader: AutoGrader,
    private val resultProjector: ExamResultProjector,
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional
    fun start(userId: UUID, examIdRaw: String): ExamSessionResponse {
        val exam = examForUser(userId, examIdRaw)
        requireSessionable(exam)
        val existing = sessionRepository.findByUserIdAndExamId(userId, exam.id)
        val now = clock.instant()
        val session = existing ?: ExamSessionEntity().apply {
            this.examId = exam.id
            this.userId = userId
            status = SessionStatus.IN_PROGRESS
            startedAt = now
            updatedAt = now
        }.also { sessionRepository.save(it) }

        val remaining = if (session.status == SessionStatus.IN_PROGRESS) {
            val elapsedSeconds = java.time.Duration.between(session.startedAt, now).seconds
            (exam.durationMinutes * 60L - elapsedSeconds).coerceAtLeast(0L).toInt()
        } else {
            0
        }
        return toSessionResponse(exam, session, remaining)
    }

    @Transactional
    fun sync(userId: UUID, examIdRaw: String, body: JsonNode): Boolean {
        val exam = examForUser(userId, examIdRaw)
        requireSessionable(exam)
        val now = clock.instant()
        val session = sessionRepository.findByUserIdAndExamId(userId, exam.id)
            ?: ExamSessionEntity().apply {
                this.examId = exam.id
                this.userId = userId
                status = SessionStatus.IN_PROGRESS
                startedAt = now
            }.also { sessionRepository.save(it) }

        if (session.status == SessionStatus.COMPLETED) return false

        body.get("answers")?.takeIf { it.isObject }?.let { answers ->
            session.answers = mapper.writeValueAsString(answers)
        }
        body.get("currentQuestionIndex")?.takeIf { it.isNumber }?.let { index ->
            session.currentIndex = index.intValue().coerceAtLeast(0)
        }
        val flagged = body.get("flaggedQuestions")?.takeIf { it.isArray }
        session.flagged = flagged?.let { mapper.writeValueAsString(it) }
        session.updatedAt = now
        sessionRepository.save(session)
        return true
    }

    @Transactional
    fun submit(userId: UUID, examIdRaw: String, answersBody: JsonNode): ExamResultPayload {
        val exam = examForUser(userId, examIdRaw)
        requireSessionable(exam)
        if (!answersBody.isObject) throw invalidArgument("submit body must be a JSON object of answers")
        val now = clock.instant()
        val session = sessionRepository.findByUserIdAndExamId(userId, exam.id)
            ?: throw ApiException(ApiErrorCode.CONFLICT, "No exam session in progress; start one first")
        if (session.status == SessionStatus.COMPLETED) {
            throw conflict("Exam already submitted")
        }

        val questions = authoringService.questionsOf(exam.id)
        val totalPoints = questions.sumOf { it.points }
        var score = 0
        var correctCount = 0

        val results = questions.map { question ->
            val userValue = answersBody.get(question.id.toString())
            val outcome = autoGrader.grade(question, userValue)
            score += outcome.pointsEarned
            if (outcome.isCorrect) correctCount++
            // Stored detail stays key-free: the projector rebuilds the student
            // view from correctness and points only.
            QuestionResultPayload(
                questionId = question.id.toString(),
                isCorrect = outcome.isCorrect,
                userAnswer = userValue?.let(::answerText),
                pointsEarned = outcome.pointsEarned,
            )
        }

        val percentage = if (totalPoints > 0) (score * 100.0 / totalPoints).roundToInt() else 0
        val timeTaken = java.time.Duration.between(session.startedAt, now).seconds.toInt().coerceAtLeast(0)

        val previous = submissionRepository.findByUserIdAndExamId(userId, exam.id)
        val submission = (previous ?: ExamSubmissionEntity().apply {
            this.examId = exam.id
            this.userId = userId
        }).apply {
            this.score = score
            this.totalPoints = totalPoints
            this.percentage = percentage
            grade = gradeFor(percentage)
            this.correctCount = correctCount
            questionCount = questions.size
            timeTakenSeconds = timeTaken
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

        return resultProjector.result(exam, submission, questions, percentileFor(submission))
    }

    @Transactional(readOnly = true)
    fun result(userId: UUID, examIdRaw: String): ExamResultPayload {
        val exam = examForUser(userId, examIdRaw)
        val submission = submissionRepository.findByUserIdAndExamId(userId, exam.id)
            ?: throw notFound("No result yet for this exam")
        return resultProjector.result(exam, submission, authoringService.questionsOf(exam.id), percentileFor(submission))
    }

    /** Exam-hub submission detail for review (doc 02 §3.4). */
    @Transactional(readOnly = true)
    fun submission(userId: UUID, examIdRaw: String): ExamSubmissionDetailsPayload {
        val exam = examForUser(userId, examIdRaw)
        val submission = submissionRepository.findByUserIdAndExamId(userId, exam.id)
            ?: throw notFound("No submission yet for this exam")
        val questions = authoringService.questionsOf(exam.id)
        val payloads = questions.map { authoringService.toQuestionPayload(it, includeKeys = false) }
        return resultProjector.submissionDetails(exam, submission, questions, payloads)
    }

    // ------------------------------------------------------------ internals

    private fun requireSessionable(exam: ExamEntity) {
        if (exam.status != ExamStatus.PUBLISHED) throw notFound("Exam not found")
        if (exam.examType == ExamType.TRADITIONAL) {
            throw invalidArgument("Traditional exams do not have online sessions")
        }
    }

    private fun examForUser(userId: UUID, examIdRaw: String): ExamEntity {
        val id = runCatching { UUID.fromString(examIdRaw) }.getOrNull()
            ?: throw invalidArgument("exam id is not a valid identifier")
        val exam = examRepository.findById(id).orElse(null) ?: throw notFound("Exam not found")
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        val visible = when (exam.scope) {
            ExamScope.GLOBAL -> true
            ExamScope.SCHOOL, ExamScope.SCHOOL_GRADE_CLASS -> {
                val sameSchool = user.schoolId != null && exam.schoolId != null && exam.schoolId == user.schoolId
                val classId = exam.classId
                sameSchool &&
                    (classId == null || membershipRepository.findByClassIdAndStudentId(classId, user.id) != null)
            }
        }
        if (!visible) throw notFound("Exam not found")
        return exam
    }

    private fun toSessionResponse(
        exam: ExamEntity,
        session: ExamSessionEntity,
        timeRemainingSeconds: Int,
    ): ExamSessionResponse {
        val questions = authoringService.questionsOf(exam.id)
            .map { authoringService.toQuestionPayload(it, includeKeys = false) }
        return ExamSessionResponse(
            id = session.id.toString(),
            examId = exam.id.toString(),
            title = exam.title,
            questions = questions,
            currentQuestionIndex = session.currentIndex,
            answers = session.answers?.let { runCatching { mapper.readTree(it) }.getOrNull() },
            startedAt = session.startedAt.toEpochMilli(),
            timeRemainingSeconds = timeRemainingSeconds,
            status = session.status.name,
            flaggedQuestions = parseIdList(session.flagged),
        )
    }

    /** Percentile rank of this submission among all attempts at the same exam. */
    private fun percentileFor(submission: ExamSubmissionEntity): Int {
        val peers = submissionRepository.findAllByExamId(submission.examId)
        if (peers.isEmpty()) return 0
        val atOrBelow = peers.count { it.percentage <= submission.percentage }
        return (100.0 * atOrBelow / peers.size).roundToInt().coerceIn(0, 100)
    }

    private fun parseIdList(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        if (!node.isArray) return null
        val out = mutableListOf<String>()
        for (i in 0 until node.size()) out.add(node.get(i).asString())
        return out
    }

    private fun answerText(node: JsonNode): String = when {
        node.isValueNode -> node.asString()
        node.isArray -> (0 until node.size()).joinToString(", ") { node.get(it).asString() }
        node.isObject -> {
            node.properties().joinToString(", ") { it.key + ": " + it.value.asString() }
        }
        else -> node.toString()
    }

    private fun gradeFor(percentage: Int): String = when {
        percentage >= 80 -> "A"
        percentage >= 70 -> "B"
        percentage >= 60 -> "C"
        percentage >= 50 -> "D"
        percentage >= 40 -> "E"
        else -> "F"
    }
}
