package com.afrithecus.brainbox.api.doubt

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.doubt.entity.DoubtAnswerEntity
import com.afrithecus.brainbox.api.doubt.entity.DoubtBookmarkEntity
import com.afrithecus.brainbox.api.doubt.entity.DoubtQuestionEntity
import com.afrithecus.brainbox.api.doubt.entity.DoubtVoteEntity
import com.afrithecus.brainbox.api.doubt.model.QuestionStatus
import com.afrithecus.brainbox.api.doubt.repository.DoubtAnswerRepository
import com.afrithecus.brainbox.api.doubt.repository.DoubtBookmarkRepository
import com.afrithecus.brainbox.api.doubt.repository.DoubtQuestionRepository
import com.afrithecus.brainbox.api.doubt.repository.DoubtVoteRepository
import com.afrithecus.brainbox.api.doubt.web.AnswerRequest
import com.afrithecus.brainbox.api.doubt.web.AskQuestionRequest
import com.afrithecus.brainbox.api.doubt.web.DoubtAnswerPayload
import com.afrithecus.brainbox.api.doubt.web.DoubtQuestionPayload
import com.afrithecus.brainbox.api.doubt.web.VoteResultPayload
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
 * (question author only), bookmarks, and up/down voting. Vote tallies are read
 * from doubt_votes (up = direction 1, down = direction -1) so the split stays
 * correct and a caller's own direction travels with every payload.
 */
@Service
class DoubtService(
    private val questionRepository: DoubtQuestionRepository,
    private val answerRepository: DoubtAnswerRepository,
    private val voteRepository: DoubtVoteRepository,
    private val bookmarkRepository: DoubtBookmarkRepository,
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
        return toQuestionPayload(question, authorName(author.id), author.id)
    }

    @Transactional(readOnly = true)
    fun list(viewerId: UUID?, subject: String?, search: String?, sort: String?): List<DoubtQuestionPayload> {
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
                compareByDescending<DoubtQuestionEntity> { it.voteCount }
                    .thenByDescending { it.createdAt }
            )
            "recent", null, "" -> result.sortedByDescending { it.createdAt }
            "unanswered" -> result.sortedByDescending { it.createdAt }
            else -> throw invalidArgument("sort must be recent, popular or unanswered")
        }
        return result.map { toQuestionPayload(it, authorName(it.authorId), viewerId) }
    }

    @Transactional
    fun detail(viewerId: UUID?, questionIdRaw: String): DoubtQuestionPayload {
        val question = findQuestion(questionIdRaw)
        question.viewCount = question.viewCount + 1
        questionRepository.save(question)
        return toQuestionPayload(question, authorName(question.authorId), viewerId)
    }

    /** Toggles the caller's bookmark and returns the refreshed question. */
    @Transactional
    fun bookmark(actor: UserEntity, questionIdRaw: String): DoubtQuestionPayload {
        val question = findQuestion(questionIdRaw)
        val existing = bookmarkRepository.findByUserIdAndQuestionId(actor.id, question.id)
        if (existing != null) {
            bookmarkRepository.delete(existing)
        } else {
            bookmarkRepository.save(
                DoubtBookmarkEntity().apply {
                    userId = actor.id
                    this.questionId = question.id
                    createdAt = clock.instant()
                }
            )
        }
        return toQuestionPayload(question, authorName(question.authorId), actor.id)
    }

    // -------------------------------------------------------------- answers

    @Transactional(readOnly = true)
    fun answers(viewerId: UUID?, questionIdRaw: String): List<DoubtAnswerPayload> {
        val question = findQuestion(questionIdRaw)
        return answerRepository.findAllByQuestionId(question.id)
            .sortedWith(compareByDescending<DoubtAnswerEntity> { it.isAccepted }
                .thenByDescending { it.voteCount }
                .thenBy { it.createdAt })
            .map { toAnswerPayload(it, authorName(it.authorId), viewerId) }
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
        return toAnswerPayload(answer, authorName(author.id), author.id)
    }

    /** Only the question author may accept; returns the refreshed question. */
    @Transactional
    fun acceptAnswer(actor: UserEntity, answerIdRaw: String): DoubtQuestionPayload {
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
        return toQuestionPayload(question, authorName(question.authorId), actor.id)
    }

    // --------------------------------------------------------------- voting

    /**
     * Toggle semantics: a first vote records the direction, the same direction
     * again removes it, and the opposite direction switches it. New up/down
     * tallies are counted from doubt_votes.
     */
    @Transactional
    fun vote(voter: UserEntity, targetType: String, targetIdRaw: String, voteType: String): VoteResultPayload {
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
        val message = when {
            existing == null -> {
                voteRepository.save(
                    DoubtVoteEntity().apply {
                        voterId = voter.id
                        this.targetType = normalizedTarget
                        this.targetId = targetId
                        this.direction = direction
                        createdAt = clock.instant()
                    }
                )
                "Vote recorded"
            }
            existing.direction == direction -> {
                voteRepository.delete(existing)
                "Vote removed"
            }
            else -> {
                existing.direction = direction
                voteRepository.save(existing)
                "Vote updated"
            }
        }
        syncVoteCount(normalizedTarget, targetId)
        return VoteResultPayload(
            success = true,
            newUpvotes = upvotes(normalizedTarget, targetId),
            newDownvotes = downvotes(normalizedTarget, targetId),
            message = message,
        )
    }

    /** Keeps the denormalised vote_count column equal to up minus down. */
    private fun syncVoteCount(targetType: String, targetId: UUID) {
        val score = upvotes(targetType, targetId) - downvotes(targetType, targetId)
        when (targetType) {
            "question" -> {
                val question = findQuestionById(targetId)
                question.voteCount = score
                questionRepository.save(question)
            }
            else -> {
                val answer = findAnswerById(targetId)
                answer.voteCount = score
                answer.updatedAt = clock.instant()
                answerRepository.save(answer)
            }
        }
    }

    // ------------------------------------------------------------ internals

    private fun upvotes(targetType: String, targetId: UUID): Int =
        voteRepository.countByTargetTypeAndTargetIdAndDirection(targetType, targetId, 1).toInt()

    private fun downvotes(targetType: String, targetId: UUID): Int =
        voteRepository.countByTargetTypeAndTargetIdAndDirection(targetType, targetId, -1).toInt()

    private fun currentVote(viewerId: UUID?, targetType: String, targetId: UUID): Int =
        viewerId?.let {
            voteRepository.findByVoterIdAndTargetTypeAndTargetId(it, targetType, targetId)?.direction
        } ?: 0

    private fun bookmarked(viewerId: UUID?, questionId: UUID): Boolean =
        viewerId != null && bookmarkRepository.existsByUserIdAndQuestionId(viewerId, questionId)

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

    private fun toQuestionPayload(q: DoubtQuestionEntity, author: String, viewerId: UUID?): DoubtQuestionPayload {
        val accepted = answerRepository.findByQuestionIdAndIsAcceptedTrue(q.id)
        return DoubtQuestionPayload(
            id = q.id.toString(),
            title = q.title,
            body = q.body,
            subject = q.subject,
            tags = codec.parseList(q.tags),
            authorId = q.authorId.toString(),
            authorName = author,
            status = q.status.name,
            upvotes = upvotes("question", q.id),
            downvotes = downvotes("question", q.id),
            answerCount = answerRepository.countByQuestionId(q.id).toInt(),
            viewCount = q.viewCount,
            createdAt = q.createdAt.toEpochMilli(),
            isAcceptedAnswer = accepted != null,
            acceptedAnswerId = accepted?.id?.toString(),
            currentUserVote = currentVote(viewerId, "question", q.id),
            isBookmarked = bookmarked(viewerId, q.id),
        )
    }

    private fun toAnswerPayload(a: DoubtAnswerEntity, author: String, viewerId: UUID?): DoubtAnswerPayload =
        DoubtAnswerPayload(
            id = a.id.toString(),
            questionId = a.questionId.toString(),
            body = a.body,
            authorId = a.authorId.toString(),
            authorName = author,
            authorRole = a.authorRole,
            isAccepted = a.isAccepted,
            upvotes = upvotes("answer", a.id),
            downvotes = downvotes("answer", a.id),
            currentUserVote = currentVote(viewerId, "answer", a.id),
            createdAt = a.createdAt.toEpochMilli(),
        )
}
