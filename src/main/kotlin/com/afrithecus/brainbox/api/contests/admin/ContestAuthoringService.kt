package com.afrithecus.brainbox.api.contests.admin

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.entity.ContestQuestionEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.model.ContestScope
import com.afrithecus.brainbox.api.contests.repository.ContestQuestionRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.contests.web.ContestParticipantPayload
import com.afrithecus.brainbox.api.contests.web.ContestPayload
import com.afrithecus.brainbox.api.contests.web.CreateContestQuestionRequest
import com.afrithecus.brainbox.api.contests.web.CreateContestRequest
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Contest authoring.
 *
 * Two surfaces share one service:
 *  - ADMIN: BrainBox-team GLOBAL contests (the original behaviour, still PUBLISHED at create).
 *  - TEACHER: SCHOOL_GRADE contests for the teacher's own school. Create is a DRAFT; publish is a
 *    separate step; update is allowed while DRAFT; cancel/finalise/results are author-only.
 *
 * Contests are grade-level resources: a SCHOOL_GRADE contest is visible to every class in that
 * school at that grade. There is deliberately no per-class targeting.
 */
@Service
class ContestAuthoringService(
    private val contestRepository: ContestRepository,
    private val questionRepository: ContestQuestionRepository,
    private val registrationRepository: ContestRegistrationRepository,
    private val submissionRepository: ContestSubmissionRepository,
    private val userRepository: UserRepository,
    private val codec: QuestionCodec,
    private val notifications: NotificationService,
    private val clock: Clock,
) {

    // ---------------------------------------------------------------- admin (global)

    @Transactional
    fun create(request: CreateContestRequest, authorUserId: UUID): ContestPayload {
        // A global contest is published immediately, so an empty paper must be refused here.
        if (request.questions.isEmpty()) throw invalidArgument("A published contest needs at least one question")
        val contest = newContest(request, authorUserId)
        contest.scope = parseScope(request.scope) ?: ContestScope.GLOBAL
        contest.schoolId = null
        // The BrainBox team's contests are published immediately, as before.
        contest.lifecycle = ContestLifecycle.PUBLISHED
        contestRepository.save(contest)
        replaceQuestions(contest, request.questions)
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

    // ---------------------------------------------------------------- teacher (school + grade)

    @Transactional
    fun createTeacher(request: CreateContestRequest, authorUserId: UUID): ContestPayload {
        val author = requireTeacherWithSchool(authorUserId)
        // Idempotent retry: the same clientId from the same author returns the existing draft.
        request.clientId?.takeIf { it.isNotBlank() }?.let { clientId ->
            contestRepository.findAllByCreatedByOrderByStartTimeDesc(author.id)
                .firstOrNull { it.scope == ContestScope.SCHOOL_GRADE && it.title == request.title.trim() && it.grade == request.grade.trim() }
                ?.let { return toPayload(it, true, true) }
        }
        val contest = newContest(request, author.id)
        contest.scope = ContestScope.SCHOOL_GRADE
        contest.schoolId = author.schoolId
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "Your account has no school; a school contest needs one")
        contest.grade = request.grade.trim()
        contest.rules = request.rules?.trim()?.takeIf { it.isNotBlank() }
        contest.lifecycle = ContestLifecycle.DRAFT
        contestRepository.save(contest)
        replaceQuestions(contest, request.questions)
        return toPayload(contest, includeQuestions = true, includeKeys = true)
    }

    @Transactional
    fun updateTeacher(contestIdRaw: String, request: CreateContestRequest, authorUserId: UUID): ContestPayload {
        val contest = requireOwned(contestIdRaw, authorUserId)
        if (contest.lifecycle != ContestLifecycle.DRAFT) {
            throw conflict("Only a draft contest can be edited")
        }
        if (submissionRepository.findAllByContestId(contest.id).isNotEmpty()) {
            throw conflict("This contest already has submissions; its questions are frozen")
        }
        contest.title = request.title.trim()
        contest.subject = request.subject.trim()
        contest.grade = request.grade.trim()
        contest.startTime = Instant.ofEpochMilli(request.startTime)
        contest.endTime = Instant.ofEpochMilli(request.endTime)
        if (!contest.endTime.isAfter(contest.startTime)) throw invalidArgument("endTime must be after startTime")
        contest.entryFee = request.entryFee
        contest.prize = request.prize?.trim()?.takeIf { it.isNotEmpty() }
        contest.maxParticipants = request.maxParticipants
        contest.difficulty = request.difficulty
        contest.rules = request.rules?.trim()?.takeIf { it.isNotBlank() }
        contestRepository.save(contest)
        replaceQuestions(contest, request.questions)
        return toPayload(contest, includeQuestions = true, includeKeys = true)
    }

    @Transactional
    fun publishTeacher(contestIdRaw: String, authorUserId: UUID): ContestPayload {
        val contest = requireOwned(contestIdRaw, authorUserId)
        if (contest.lifecycle == ContestLifecycle.CANCELLED) throw conflict("A cancelled contest cannot be published")
        if (contest.lifecycle == ContestLifecycle.PUBLISHED) return toPayload(contest, true, true)
        val questions = questionsOf(contest.id)
        if (questions.isEmpty()) throw conflict("Add at least one question before publishing")
        if (!contest.endTime.isAfter(clock.instant())) throw conflict("endTime must be in the future to publish")
        contest.lifecycle = ContestLifecycle.PUBLISHED
        contestRepository.save(contest)
        return toPayload(contest, true, true)
    }

    @Transactional
    fun finaliseTeacher(contestIdRaw: String, authorUserId: UUID): ContestPayload {
        val contest = requireOwned(contestIdRaw, authorUserId)
        if (contest.lifecycle != ContestLifecycle.PUBLISHED) throw conflict("Only a published contest can be finalised")
        if (contest.finalisedAt == null) {
            contest.finalisedAt = clock.instant()
            // Closing early must close the submission window too.
            if (contest.endTime.isAfter(clock.instant())) contest.endTime = clock.instant()
            contestRepository.save(contest)
        }
        return toPayload(contest, true, true)
    }

    @Transactional
    fun cancelTeacher(contestIdRaw: String, authorUserId: UUID) {
        val contest = requireOwned(contestIdRaw, authorUserId)
        if (contest.resultsPublishedAt != null) throw conflict("Results are already published")
        contest.lifecycle = ContestLifecycle.CANCELLED
        contestRepository.save(contest)
    }

    @Transactional(readOnly = true)
    fun listTeacher(authorUserId: UUID): List<ContestPayload> {
        requireTeacher(authorUserId)
        return contestRepository.findAllByCreatedByOrderByStartTimeDesc(authorUserId)
            .map { toPayload(it) }
    }

    @Transactional(readOnly = true)
    fun getTeacher(contestIdRaw: String, authorUserId: UUID): ContestPayload =
        toPayload(requireOwned(contestIdRaw, authorUserId), includeQuestions = true, includeKeys = true)

    @Transactional(readOnly = true)
    fun participantsTeacher(contestIdRaw: String, authorUserId: UUID): List<ContestParticipantPayload> {
        val contest = requireOwned(contestIdRaw, authorUserId)
        val submissions = submissionRepository.findAllByContestId(contest.id).associateBy { it.userId }
        return registrationRepository.findAllByContestId(contest.id).map { registration ->
            val user = userRepository.findById(registration.studentId).orElse(null)
            val submission = submissions[registration.studentId]
            ContestParticipantPayload(
                userId = registration.studentId.toString(),
                name = user?.name ?: "Student",
                grade = user?.gradeLevel,
                registeredAt = registration.registeredAt.toEpochMilli(),
                submittedAt = submission?.submittedAt?.toEpochMilli(),
                score = submission?.score,
                percentage = submission?.percentage,
                integrityFlags = emptyList(),
            )
        }
    }

    /**
     * Publish results to every registrant. Ranks only stabilise after the window closes (or the
     * author finalises), so this is the moment to notify - never submit time.
     */
    @Transactional
    fun publishResultsTeacher(contestIdRaw: String, authorUserId: UUID): ContestPayload {
        val contest = requireOwned(contestIdRaw, authorUserId)
        if (contest.lifecycle != ContestLifecycle.PUBLISHED) throw conflict("Only a published contest can publish results")
        val finished = contest.finalisedAt != null || !clock.instant().isBefore(contest.endTime)
        if (!finished) throw conflict("Finalise the contest or wait for it to end before publishing results")
        if (contest.resultsPublishedAt == null) {
            val now = clock.instant()
            contest.resultsPublishedAt = now
            contestRepository.save(contest)
            registrationRepository.findAllByContestId(contest.id).forEach { registration ->
                notifications.notifyUser(
                    userId = registration.studentId,
                    title = "Contest results ready",
                    message = "Results for " + contest.title + " are ready.",
                    type = NotificationType.SYSTEM,
                    urgency = NotificationUrgency.HIGH,
                    actionRoute = "contest_detail/" + contest.id,
                    actionLabel = "Open contest",
                    metadata = mapOf("kind" to "contest_results", "contestId" to contest.id.toString(), "title" to contest.title),
                )
            }
        }
        return toPayload(contest, true, true)
    }

    // ---------------------------------------------------------------- internals

    private fun newContest(request: CreateContestRequest, authorUserId: UUID): ContestEntity {
        val start = Instant.ofEpochMilli(request.startTime)
        val end = Instant.ofEpochMilli(request.endTime)
        if (!end.isAfter(start)) throw invalidArgument("endTime must be after startTime")
        val author = userRepository.findById(authorUserId).orElse(null)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "Author not found")
        return ContestEntity().apply {
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
        }
    }

    private fun replaceQuestions(contest: ContestEntity, requests: List<CreateContestQuestionRequest>) {
        questionRepository.deleteAll(questionRepository.findAllByContestIdOrderByOrderIndexAsc(contest.id))
        if (requests.isEmpty()) return
        val questions = requests.mapIndexed { index, q ->
            val qType = parseType(q.type)
            ContestQuestionEntity().apply {
                contestId = contest.id
                text = q.text
                this.qType = qType
                options = codec.toJson(q.options)
                correctAnswer = q.correctAnswer
                explanation = q.explanation
                points = q.points.coerceAtLeast(1)
                difficulty = q.difficulty
                matchingPairs = codec.toJson(q.matchingPairs)
                topic = q.topic?.trim()?.takeIf { it.isNotEmpty() }
                subtopic = q.subtopic?.trim()?.takeIf { it.isNotEmpty() }
                orderIndex = index
            }
        }
        questionRepository.saveAll(questions)
    }

    private fun requireTeacher(authorUserId: UUID) {
        val author = userRepository.findById(authorUserId).orElse(null)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "Author not found")
        if (author.role != Role.TEACHER) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only a teacher can author a school contest")
        }
    }

    private fun requireTeacherWithSchool(authorUserId: UUID) =
        userRepository.findById(authorUserId).orElse(null)
            ?.takeIf { it.role == Role.TEACHER }
            ?: throw ApiException(ApiErrorCode.FORBIDDEN, "Only a teacher can author a school contest")

    private fun requireOwned(contestIdRaw: String, authorUserId: UUID): ContestEntity {
        requireTeacher(authorUserId)
        val contest = findContest(contestIdRaw)
        if (contest.createdBy != authorUserId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your contest")
        }
        return contest
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
        val author = userRepository.findById(contest.createdBy).orElse(null)
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
            lifecycle = contest.lifecycle.name,
            scope = contest.scope.name,
            schoolId = contest.schoolId?.toString(),
            authorName = author?.name,
            authorRole = if (author?.role == Role.TEACHER) "TEACHER" else "BRAINBOX",
            questionCount = questions.size,
            totalPoints = questions.sumOf { it.points },
            rules = contest.rules,
            resultsPublished = contest.resultsPublishedAt != null,
            finalised = contest.finalisedAt != null,
        )
    }

    internal fun statusOf(contest: ContestEntity): com.afrithecus.brainbox.api.contests.model.ContestStatus {
        val now = clock.instant()
        return when {
            contest.finalisedAt != null -> com.afrithecus.brainbox.api.contests.model.ContestStatus.COMPLETED
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

    private fun parseScope(raw: String?): ContestScope? =
        raw?.trim()?.takeIf { it.isNotBlank() }?.let {
            runCatching { ContestScope.valueOf(it.uppercase()) }.getOrNull()
                ?: throw invalidArgument("scope has an invalid value")
        } ?: null
}
