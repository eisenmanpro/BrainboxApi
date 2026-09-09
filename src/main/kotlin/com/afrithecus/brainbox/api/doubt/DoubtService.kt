package com.afrithecus.brainbox.api.doubt

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.doubt.entity.DoubtAnswerEntity
import com.afrithecus.brainbox.api.doubt.entity.DoubtQuestionEntity
import com.afrithecus.brainbox.api.doubt.entity.DoubtVoteEntity
import com.afrithecus.brainbox.api.doubt.model.QuestionStatus
import com.afrithecus.brainbox.api.doubt.repository.DoubtAnswerRepository
import com.afrithecus.brainbox.api.doubt.repository.DoubtQuestionRepository
import com.afrithecus.brainbox.api.doubt.repository.DoubtVoteRepository
import com.afrithecus.brainbox.api.doubt.web.AnswerRequest
import com.afrithecus.brainbox.api.doubt.web.AskQuestionRequest
import com.afrithecus.brainbox.api.doubt.web.DoubtAnswerPayload
import com.afrithecus.brainbox.api.doubt.web.DoubtQuestionPayload
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Doubt solving forum (doc 05 §3): ask/list/detail, answers, accept-answer
 * (question author only), and up/down voting with no double counting.
 */
@Service
class DoubtService(
    private val questionRepository: DoubtQuestionRepository,
    private val answerRepository: DoubtAnswerRepository,
    private val voteRepository: DoubtVoteRepository,
    private val userRepository: UserRepository,
    private val codec: QuestionCodec,
    private val clock: Clock,
) {

    // ------------------------------------------------------------- questions

    @Transactional
    fun ask(author: UserEntity, request: AskQuestionRequest): DoubtQuestionPayload {
        val question = DoubtQuestionEntity().apply {
            title = request.title.trim()
            body = request.body.trim()
            subject = request.subject.trim()
            tags = codec.toJson(request.tags)
            authorId = author.id
            status = QuestionStatus.OPEN
        }
        questionRepository.save(question)
        return toQuestionPayload(question, authorName(author.id))
    }

    @Transactional(readOnly = true)
    fun list(subject: String?, search: String?, sort: String?): List<DoubtQuestionPayload> {
        val all = questionRepository.findAll()
        val unansweredIds = if (sort == "unanswered") {
            all.filter { q -> answerRepository.countByQuestionId(q.id) == 0L }.map { it.id }.toSet()
        } else {
            emptySet()
        }
        var result = all
        if (subject != null && subject.isNotBlank()) {
            result = result.filter { it.subject.equals(subject.trim(), ignoreCase = true) }
        }
        if (search != null && search.isNotBlank()) {
            val q = search.trim()
            result = result.filter {
                it.title.contains(q, ignoreCase = true) || it.body.contains(q, ignoreCase = true) ||
                    it.subject.contains(q, ignoreCase = true)
            }
        }
        if (sort == "unanswered") {
            result = result.filter { it.id in unansweredIds }
        }
        result = when (sort) {
            "popular" -> result.sortedWith(
                compareByDescending<com.afrithecus.brainbox.api.doubt.entity.DoubtQuestionEntity> { it.voteCount }
                    .thenByDescending { it.createdAt }
            )
            "recent", null, "" -> result.sortedByDescending { it.createdAt }
            "unanswered" -> result.sortedByDescending { it.createdAt }
            else -> throw invalidArgument("sort must be recent, popular or unanswered")
        }
        return result.map { toQuestionPayload(it, authorName(it.authorId)) }
    }

    @Transactional
    fun detail(questionIdRaw: String): DoubtQuestionPayload {
        val question = findQuestion(questionIdRaw)
        question.viewCount = question.viewCount + 1
        questionRepository.save(question)
        return toQuestionPayload(question, authorName(question.authorId))
    }

    // -------------------------------------------------------------- answers

    @Transactional(readOnly = true)
    fun answers(questionIdRaw: String): List<DoubtAnswerPayload> {
        val question = findQuestion(questionIdRaw)
        return answerRepository.findAllByQuestionId(question.id)
            .sortedWith(compareByDescending<DoubtAnswerEntity> { it.isAccepted }
                .thenByDescending { it.voteCount }
                .thenBy { it.createdAt })
            .map { toAnswerPayload(it, authorName(it.authorId)) }
    }

    @Transactional
    fun postAnswer(author: UserEntity, questionIdRaw: String, request: AnswerRequest): DoubtAnswerPayload {
        if (author.role != Role.TEACHER && author.role != Role.STUDENT) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only teachers and students may answer doubts")
        }
        val question = findQuestion(questionIdRaw)
        if (question.status == QuestionStatus.CLOSED) throw conflict("Question is closed")
        val answer = DoubtAnswerEntity().apply {
            this.questionId = question.id
            body = request.body.trim()
            authorId = author.id
            authorRole = if (author.role == Role.TEACHER) "TEACHER" else "STUDENT"
            createdAt = clock.instant()
            updatedAt = clock.instant()
        }
        answerRepository.save(answer)
        if (question.status == QuestionStatus.OPEN) {
            question.status = QuestionStatus.ANSWERED
            questionRepository.save(question)
        }
        return toAnswerPayload(answer, authorName(author.id))
    }

    @Transactional
    fun acceptAnswer(actor: UserEntity, answerIdRaw: String) {
        val answer = findAnswer(answerIdRaw)
        val question = questionRepository.findById(answer.questionId).orElse(null)
            ?: throw notFound("Question not found")
        if (question.authorId != actor.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only the question author can accept an answer")
        }
        answerRepository.findAllByQuestionId(question.id).forEach {
            it.isAccepted = it.id == answer.id
            it.updatedAt = clock.instant()
        }
        question.status = QuestionStatus.CLOSED
        questionRepository.save(question)
    }

    // --------------------------------------------------------------- voting

    @Transactional
    fun vote(voter: UserEntity, targetType: String, targetIdRaw: String, voteType: String) {
        val normalizedTarget = when (targetType) {
            "question", "answer" -> targetType
            else -> throw invalidArgument("vote target must be question or answer")
        }
        val direction = when (voteType.trim().lowercase()) {
            "up" -> 1
            "down" -> -1
            else -> throw invalidArgument("voteType must be up or down")
        }
        val targetId = parseUuid(targetIdRaw, "targetId")
        when (normalizedTarget) {
            "question" -> findQuestionById(targetId)
            else -> findAnswerById(targetId)
        }
        val existing = voteRepository.findByVoterIdAndTargetTypeAndTargetId(voter.id, normalizedTarget, targetId)
        if (existing != null) {
            if (existing.direction == direction) return // same vote is a no-op
            applyVoteDelta(normalizedTarget, targetId, -existing.direction)
            existing.direction = direction
            voteRepository.save(existing)
        } else {
            voteRepository.save(
                DoubtVoteEntity().apply {
                    voterId = voter.id
                    this.targetType = normalizedTarget
                    this.targetId = targetId
                    this.direction = direction
                    createdAt = clock.instant()
                }
            )
        }
        applyVoteDelta(normalizedTarget, targetId, direction)
    }

    private fun applyVoteDelta(targetType: String, targetId: UUID, delta: Int) {
        when (targetType) {
            "question" -> {
                val q = findQuestionById(targetId)
                q.voteCount = (q.voteCount + delta).coerceAtLeast(0)
                questionRepository.save(q)
            }
            else -> {
                val a = findAnswerById(targetId)
                a.voteCount = (a.voteCount + delta).coerceAtLeast(0)
                a.updatedAt = clock.instant()
                answerRepository.save(a)
            }
        }
    }

    // ------------------------------------------------------------ internals

    private fun findQuestion(raw: String): DoubtQuestionEntity =
        findQuestionById(parseUuid(raw, "questionId"))

    private fun findQuestionById(id: UUID): DoubtQuestionEntity =
        questionRepository.findById(id).orElseThrow { notFound("Question not found") }

    private fun findAnswer(raw: String): DoubtAnswerEntity =
        findAnswerById(parseUuid(raw, "answerId"))

    private fun findAnswerById(id: UUID): DoubtAnswerEntity =
        answerRepository.findById(id).orElseThrow { notFound("Answer not found") }

    private fun authorName(userId: UUID): String =
        userRepository.findById(userId).map { it.name }.orElse("User")

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun toQuestionPayload(q: DoubtQuestionEntity, author: String) = DoubtQuestionPayload(
        id = q.id.toString(),
        title = q.title,
        body = q.body,
        subject = q.subject,
        tags = codec.parseList(q.tags),
        authorId = q.authorId.toString(),
        authorName = author,
        status = q.status.name,
        voteCount = q.voteCount,
        viewCount = q.viewCount,
        createdAt = q.createdAt.toEpochMilli(),
    )

    private fun toAnswerPayload(a: DoubtAnswerEntity, author: String) = DoubtAnswerPayload(
        id = a.id.toString(),
        questionId = a.questionId.toString(),
        body = a.body,
        authorId = a.authorId.toString(),
        authorName = author,
        authorRole = a.authorRole,
        isAccepted = a.isAccepted,
        voteCount = a.voteCount,
        createdAt = a.createdAt.toEpochMilli(),
    )
}
