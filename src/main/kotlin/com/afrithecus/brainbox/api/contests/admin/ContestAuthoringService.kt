package com.afrithecus.brainbox.api.contests.admin

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.entity.ContestQuestionEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.repository.ContestQuestionRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.contests.web.ContestPayload
import com.afrithecus.brainbox.api.contests.web.CreateContestQuestionRequest
import com.afrithecus.brainbox.api.contests.web.CreateContestRequest
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Contest authoring (ADMIN gate for now; teacher/operator flows later). */
@Service
class ContestAuthoringService(
    private val contestRepository: ContestRepository,
    private val questionRepository: ContestQuestionRepository,
    private val registrationRepository: ContestRegistrationRepository,
    private val submissionRepository: ContestSubmissionRepository,
    private val userRepository: UserRepository,
    private val codec: QuestionCodec,
    private val clock: Clock,
) {

    @Transactional
    fun create(request: CreateContestRequest, authorUserId: UUID): ContestPayload {
        val start = Instant.ofEpochMilli(request.startTime)
        val end = Instant.ofEpochMilli(request.endTime)
        if (!end.isAfter(start)) throw invalidArgument("endTime must be after startTime")
        val author = userRepository.findById(authorUserId).orElse(null)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "Author not found")

        val contest = ContestEntity().apply {
            title = request.title.trim()
            subject = request.subject.trim()
            grade = request.grade.trim()
            startTime = start
            endTime = end
            entryFee = request.entryFee
            prize = request.prize?.trim()?.takeIf { it.isNotEmpty() }
            maxParticipants = request.maxParticipants
            difficulty = request.difficulty
            createdBy = author.id
            lifecycle = ContestLifecycle.PUBLISHED
        }
        contestRepository.save(contest)

        val questions = request.questions.mapIndexed { index, q ->
            val qType = parseType(q.type)
            ContestQuestionEntity().apply {
                contestId = contest.id
                text = q.text
                this.qType = qType
                options = codec.toJson(q.options)
                correctAnswer = q.correctAnswer
                explanation = q.explanation
                points = q.points
                difficulty = q.difficulty
                matchingPairs = codec.toJson(q.matchingPairs)
                topic = q.topic?.trim()?.takeIf { it.isNotEmpty() }
                subtopic = q.subtopic?.trim()?.takeIf { it.isNotEmpty() }
                orderIndex = index
            }
        }
        questionRepository.saveAll(questions)
        return toPayload(contest, includeQuestions = true, includeKeys = true)
    }

    @Transactional(readOnly = true)
    fun list(): List<ContestPayload> =
        contestRepository.findAllByLifecycle(ContestLifecycle.PUBLISHED)
            .sortedBy { it.startTime }
            .map { toPayload(it) }

    @Transactional(readOnly = true)
    fun get(contestIdRaw: String): ContestPayload = toPayload(findContest(contestIdRaw), true, true)

    @Transactional
    fun cancel(contestIdRaw: String) {
        val contest = findContest(contestIdRaw)
        contest.lifecycle = ContestLifecycle.CANCELLED
        contestRepository.save(contest)
    }

    private fun findContest(raw: String): ContestEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("contest id is not a valid identifier")
        return contestRepository.findById(id).orElseThrow { notFound("Contest not found") }
    }

    internal fun toPayload(
        contest: ContestEntity,
        includeQuestions: Boolean = false,
        includeKeys: Boolean = false,
    ): ContestPayload {
        val questions = questionRepository.findAllByContestIdOrderByOrderIndexAsc(contest.id)
        return ContestPayload(
            id = contest.id.toString(),
            title = contest.title,
            subject = contest.subject,
            grade = contest.grade,
            status = statusOf(contest).name,
            startTime = contest.startTime.toEpochMilli(),
            endTime = contest.endTime.toEpochMilli(),
            entryFee = contest.entryFee,
            prize = contest.prize,
            maxParticipants = contest.maxParticipants,
            registeredCount = registrationRepository.countByContestId(contest.id).toInt(),
            questions = if (includeQuestions) questions.map { toQuestionPayload(it, includeKeys) } else null,
            isUserRegistered = false,
        )
    }

    internal fun statusOf(contest: ContestEntity): com.afrithecus.brainbox.api.contests.model.ContestStatus {
        val now = clock.instant()
        return when {
            now.isBefore(contest.startTime) -> com.afrithecus.brainbox.api.contests.model.ContestStatus.UPCOMING
            now.isBefore(contest.endTime) -> com.afrithecus.brainbox.api.contests.model.ContestStatus.ONGOING
            else -> com.afrithecus.brainbox.api.contests.model.ContestStatus.COMPLETED
        }
    }

    internal fun toQuestionPayload(question: ContestQuestionEntity, includeKeys: Boolean): QuestionPayload =
        QuestionPayload(
            id = question.id.toString(),
            text = question.text,
            type = question.qType.name,
            options = codec.parseList(question.options),
            correctAnswer = if (includeKeys) question.correctAnswer else null,
            explanation = if (includeKeys) question.explanation else null,
            points = question.points,
            difficulty = question.difficulty,
            matchingPairs = if (includeKeys) codec.parseMap(question.matchingPairs) else null,
            topic = question.topic,
            subtopic = question.subtopic,
        )

    internal fun questionsOf(contestId: UUID): List<ContestQuestionEntity> =
        questionRepository.findAllByContestIdOrderByOrderIndexAsc(contestId)

    private fun parseType(raw: String): QuestionType =
        runCatching { QuestionType.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("question type has an invalid value")
}
