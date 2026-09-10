package com.afrithecus.brainbox.api.interview

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.interview.entity.InterviewAnswerEntity
import com.afrithecus.brainbox.api.interview.entity.InterviewQuestionEntity
import com.afrithecus.brainbox.api.interview.entity.InterviewSessionEntity
import com.afrithecus.brainbox.api.interview.entity.InterviewSessionQuestionEntity
import com.afrithecus.brainbox.api.interview.model.InterviewMode
import com.afrithecus.brainbox.api.interview.model.InterviewRubricType
import com.afrithecus.brainbox.api.interview.model.InterviewStatus
import com.afrithecus.brainbox.api.interview.model.PracticeType
import com.afrithecus.brainbox.api.interview.repository.InterviewAnswerRepository
import com.afrithecus.brainbox.api.interview.repository.InterviewQuestionRepository
import com.afrithecus.brainbox.api.interview.repository.InterviewSessionQuestionRepository
import com.afrithecus.brainbox.api.interview.repository.InterviewSessionRepository
import com.afrithecus.brainbox.api.interview.web.CategoryPerformancePayload
import com.afrithecus.brainbox.api.interview.web.InterviewAnalyticsPayload
import com.afrithecus.brainbox.api.interview.web.InterviewAnswerPayload
import com.afrithecus.brainbox.api.interview.web.InterviewQuestionPayload
import com.afrithecus.brainbox.api.interview.web.InterviewResultPayload
import com.afrithecus.brainbox.api.interview.web.InterviewSessionPayload
import com.afrithecus.brainbox.api.interview.web.InterviewTrendPointPayload
import com.afrithecus.brainbox.api.interview.web.PastAttemptPayload
import com.afrithecus.brainbox.api.interview.web.RubricCriterionPayload
import com.afrithecus.brainbox.api.interview.web.RubricScorePayload
import com.afrithecus.brainbox.api.interview.web.StartInterviewRequest
import com.afrithecus.brainbox.api.interview.web.SubmitAnswerRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Mock interviews (doc 06 §2). The server owns session state and scores every
 * answer; the client only relays transcribed text (and optional on-device emotion
 * metadata, stored for analytics).
 */
@Service
class InterviewService(
    private val questionRepository: InterviewQuestionRepository,
    private val sessionRepository: InterviewSessionRepository,
    private val sessionQuestionRepository: InterviewSessionQuestionRepository,
    private val answerRepository: InterviewAnswerRepository,
    private val engine: InterviewScoringEngine,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun questions(typeRaw: String, difficulty: Int): List<InterviewQuestionPayload> {
        val type = practiceType(typeRaw)
        return questionRepository.findAllByTypeOrderByOrderIndexAsc(type)
            .map { toQuestionPayload(it, difficulty.coerceIn(1, 5)) }
    }

    @Transactional
    fun start(current: CurrentUser, request: StartInterviewRequest): InterviewSessionPayload {
        requireSelf(current, request.userId)
        val type = practiceType(request.type)
        val mode = interviewMode(request.mode)
        val difficulty = request.difficulty.coerceIn(1, 5)
        val questions = questionRepository.findAllByTypeOrderByOrderIndexAsc(type)
        if (questions.isEmpty()) throw invalidArgument("No questions available for " + type.name)
        val now = clock.instant()
        val session = sessionRepository.save(InterviewSessionEntity().apply {
            this.userId = current.userId
            this.type = type
            this.mode = mode
            this.difficulty = difficulty
            status = InterviewStatus.IN_PROGRESS
            startedAt = now
        })
        questions.forEachIndexed { index, question ->
            sessionQuestionRepository.save(InterviewSessionQuestionEntity().apply {
                this.sessionId = session.id
                this.questionId = question.id
                orderIndex = index
            })
        }
        return InterviewSessionPayload(
            id = session.id.toString(),
            type = type.name,
            mode = mode.name,
            questions = questions.map { toQuestionPayload(it, difficulty) },
            startedAt = now.toEpochMilli(),
        )
    }

    @Transactional
    fun submitAnswer(current: CurrentUser, request: SubmitAnswerRequest): InterviewAnswerPayload {
        val session = ownedSession(current, request.sessionId)
        if (session.status != InterviewStatus.IN_PROGRESS) throw conflict("Interview already completed")
        val questionId = parseUuid(request.questionId, "question id")
        val links = sessionQuestionRepository.findAllBySessionIdOrderByOrderIndexAsc(session.id)
        if (links.none { it.questionId == questionId }) throw notFound("Question is not part of this session")
        val question = questionRepository.findById(questionId).orElse(null) ?: throw notFound("Question not found")
        val evaluation = engine.evaluate(question, request.transcribedText, session.mode, session.difficulty)
        val entity = (answerRepository.findBySessionIdAndQuestionId(session.id, questionId)
            ?: InterviewAnswerEntity().apply {
                this.sessionId = session.id
                this.questionId = questionId
            }).apply {
            transcribedText = request.transcribedText.trim()
            wordCount = evaluation.wordCount
            clarityScore = evaluation.clarityScore
            fillerWordCount = evaluation.fillerWordCount
            fillerReplacements = mapper.writeValueAsString(evaluation.fillerReplacements)
            pace = evaluation.pace
            keywordMatchCount = evaluation.keywordMatchCount
            totalKeywords = evaluation.totalKeywords
            structurePhrasesFound = evaluation.structurePhrasesFound
            feedback = evaluation.feedback
            score = evaluation.score
            rubricJson = evaluation.rubric?.let { mapper.writeValueAsString(it) }
            dictionScore = evaluation.dictionScore
            pronunciationScore = evaluation.pronunciationScore
            request.primaryEmotion?.takeIf { it.isNotBlank() }?.let { emotion = it.trim().uppercase() }
            request.emotionConfidence?.let { emotionConfidence = it }
            createdAt = clock.instant()
        }
        answerRepository.save(entity)
        return toAnswerPayload(entity)
    }

    @Transactional
    fun complete(current: CurrentUser, sessionIdRaw: String): InterviewResultPayload {
        val session = ownedSession(current, sessionIdRaw)
        val answers = answerRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id)
        if (session.status != InterviewStatus.COMPLETED) {
            session.status = InterviewStatus.COMPLETED
            session.score = if (answers.isEmpty()) 0.0 else answers.map { it.score }.average()
            session.completedAt = clock.instant()
            sessionRepository.save(session)
        }
        return InterviewResultPayload(
            sessionId = session.id.toString(),
            overallScore = (session.score ?: 0.0).roundToInt(),
            answers = answers.map(::toAnswerPayload),
            completedAt = (session.completedAt ?: clock.instant()).toEpochMilli(),
        )
    }

    @Transactional(readOnly = true)
    fun history(current: CurrentUser, userIdRaw: String): List<PastAttemptPayload> {
        requireSelf(current, userIdRaw)
        return sessionRepository.findAllByUserIdOrderByStartedAtDesc(current.userId)
            .filter { it.status == InterviewStatus.COMPLETED }
            .map { session ->
                PastAttemptPayload(
                    id = session.id.toString(),
                    date = session.startedAt.toEpochMilli(),
                    type = session.type.name,
                    mode = session.mode.name,
                    score = (session.score ?: 0.0).roundToInt(),
                    durationMinutes = durationMinutes(session),
                )
            }
    }

    @Transactional(readOnly = true)
    fun analytics(current: CurrentUser, userIdRaw: String): InterviewAnalyticsPayload {
        requireSelf(current, userIdRaw)
        val sessions = sessionRepository.findAllByUserIdOrderByStartedAtDesc(current.userId)
            .filter { it.status == InterviewStatus.COMPLETED }
            .sortedBy { it.startedAt }
        val answers = sessions.flatMap { answerRepository.findAllBySessionIdOrderByCreatedAtAsc(it.id) }
        val questions = questionRepository.findAllById(answers.map { it.questionId }).associateBy { it.id }
        val scores = sessions.mapNotNull { it.score }
        val average = if (scores.isEmpty()) 0.0 else round2(scores.average())
        val improvement = if (sessions.size < 2) {
            0.0
        } else {
            val mid = sessions.size / 2
            val older = sessions.take(mid).mapNotNull { it.score }
            val newer = sessions.drop(mid).mapNotNull { it.score }
            if (older.isEmpty() || newer.isEmpty()) 0.0 else round2(newer.average() - older.average())
        }
        val categories = answers.groupBy { questions[it.questionId]?.category ?: "General" }
            .map { (category, list) -> CategoryPerformancePayload(category, round2(list.map { it.score }.average())) }
            .sortedByDescending { it.averageScore }
        return InterviewAnalyticsPayload(
            totalSessions = sessions.size,
            averageScore = average,
            improvementTrend = improvement,
            categoryPerformance = categories,
            commonWeaknesses = categories.filter { it.averageScore < 60.0 }.sortedBy { it.averageScore }.map { it.category },
            emotionDistribution = answers.mapNotNull { it.emotion }.groupingBy { it }.eachCount(),
            trendPoints = sessions.map { session ->
                val mine = answers.filter { it.sessionId == session.id }
                InterviewTrendPointPayload(
                    timestamp = session.startedAt.toEpochMilli(),
                    score = round2(session.score ?: 0.0),
                    clarity = round2(mine.map { it.clarityScore }.average().takeIf { !it.isNaN() } ?: 0.0),
                    pace = round2(mine.map { it.pace }.average().takeIf { !it.isNaN() } ?: 0.0),
                    fillerCount = mine.sumOf { it.fillerWordCount },
                )
            },
        )
    }

    // ------------------------------------------------------------ internals

    private fun toQuestionPayload(question: InterviewQuestionEntity, difficulty: Int): InterviewQuestionPayload {
        val advanced = difficulty > 1
        val keywords = parseList(question.keywords).let { if (advanced) it + engine.advancedKeywords() else it }
        return InterviewQuestionPayload(
            id = question.id.toString(),
            text = if (advanced) question.text + " (Advanced)" else question.text,
            type = question.type.name,
            order = question.orderIndex,
            sampleAnswer = question.sampleAnswer,
            keywords = keywords,
            minWords = question.minWords + if (advanced) 20 else 0,
            maxWords = question.maxWords,
            structurePhrases = parseList(question.structurePhrases).ifEmpty { DEFAULT_STRUCTURE_PHRASES },
            rubricType = question.rubricType.name,
            companyFocus = question.companyFocus,
            schoolFocus = question.schoolFocus,
        )
    }

    private fun toAnswerPayload(answer: InterviewAnswerEntity): InterviewAnswerPayload =
        InterviewAnswerPayload(
            questionId = answer.questionId.toString(),
            transcribedText = answer.transcribedText,
            clarityScore = answer.clarityScore,
            fillerWordCount = answer.fillerWordCount,
            fillerReplacements = parseMap(answer.fillerReplacements),
            pace = answer.pace,
            keywordMatchCount = answer.keywordMatchCount,
            totalKeywords = answer.totalKeywords,
            structurePhrasesFound = answer.structurePhrasesFound,
            wordCount = answer.wordCount,
            feedback = answer.feedback,
            score = answer.score,
            rubricScore = answer.rubricJson?.let(::parseRubric),
            dictionScore = answer.dictionScore,
            pronunciationScore = answer.pronunciationScore,
        )

    private fun parseRubric(json: String): RubricScorePayload? {
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val criteria = node.get("criteria")?.takeIf { it.isArray }?.let { array ->
            (0 until array.size()).map { index ->
                val item = array.get(index)
                RubricCriterionPayload(
                    name = item.get("name")?.asString() ?: "",
                    score = item.get("score")?.intValue() ?: 0,
                    maxScore = item.get("maxScore")?.intValue() ?: 5,
                    feedback = item.get("feedback")?.asString() ?: "",
                )
            }
        }.orEmpty()
        return RubricScorePayload(
            type = node.get("type")?.asString() ?: InterviewRubricType.NONE.name,
            criteria = criteria,
            overallScore = node.get("overallScore")?.intValue() ?: 0,
        )
    }

    private fun ownedSession(current: CurrentUser, sessionIdRaw: String): InterviewSessionEntity {
        val id = parseUuid(sessionIdRaw, "session id")
        return sessionRepository.findByIdAndUserId(id, current.userId) ?: throw notFound("Interview session not found")
    }

    private fun durationMinutes(session: InterviewSessionEntity): Int {
        val end = session.completedAt ?: return 1
        return Duration.between(session.startedAt, end).toMinutes().toInt().coerceAtLeast(1)
    }

    private fun practiceType(raw: String): PracticeType =
        runCatching { PracticeType.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown interview type: " + raw)

    private fun interviewMode(raw: String): InterviewMode =
        runCatching { InterviewMode.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown interview mode: " + raw)

    private fun requireSelf(current: CurrentUser, userIdRaw: String) {
        if (userIdRaw != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's interviews")
        }
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return (0 until node.size()).map { node.get(it).asString() }
    }

    private fun parseMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (entry in node.properties()) out[entry.key] = entry.value.asString()
        return out
    }

    private companion object {
        val DEFAULT_STRUCTURE_PHRASES = listOf("firstly", "secondly", "finally", "in conclusion", "for example")
    }
}
