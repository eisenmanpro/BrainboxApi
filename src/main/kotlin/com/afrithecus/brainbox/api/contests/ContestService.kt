package com.afrithecus.brainbox.api.contests

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contests.admin.ContestAuthoringService
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.entity.ContestRegistrationEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.model.ContestScope
import com.afrithecus.brainbox.api.contests.model.ContestStatus
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.contests.web.ContestPayload
import com.afrithecus.brainbox.api.contests.web.RegistrationResponse
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Student contest flows (doc 05 §1.2-§1.4): window-split listings, detail with
 * per-user state, and registration with entitlement + capacity + window checks.
 */
@Service
class ContestService(
    private val contestRepository: ContestRepository,
    private val registrationRepository: ContestRegistrationRepository,
    private val submissionRepository: ContestSubmissionRepository,
    private val authoring: ContestAuthoringService,
    private val userRepository: UserRepository,
    private val subscriptionService: SubscriptionService,
    private val mapper: tools.jackson.databind.ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun listByWindow(userId: UUID, status: ContestStatus): List<ContestPayload> {
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        return published()
            .filter { visibleTo(user, it) }
            .filter { authoring.statusOf(it) == status }
            .sortedBy { it.startTime }
            .map { enrich(it, userId) }
    }

    @Transactional(readOnly = true)
    fun detail(userId: UUID, contestIdRaw: String): ContestPayload {
        val contest = findPublished(contestIdRaw)
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        // A contest outside the caller's grade/school answers 404: existence must not leak.
        if (!visibleTo(user, contest)) throw notFound("Contest not found")
        val base = authoring.toPayload(contest, includeQuestions = false, includeKeys = false)
        val registered = registrationRepository.findByStudentIdAndContestId(userId, contest.id) != null
        val withUser = enrich(contest, userId)
        val reveal = registered && authoring.statusOf(contest) == ContestStatus.ONGOING && user.role == Role.STUDENT
        return base.copy(
            questions = if (reveal) {
                authoring.questionsOf(contest.id).map { authoring.toQuestionPayload(it, includeKeys = false) }
            } else {
                null
            },
            isUserRegistered = withUser.isUserRegistered,
            userRank = withUser.userRank,
            userScore = withUser.userScore,
            registeredCount = withUser.registeredCount,
        )
    }

    @Transactional
    fun register(userId: UUID, contestIdRaw: String): RegistrationResponse {
        val contest = findPublished(contestIdRaw)
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        if (user.role != Role.STUDENT) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "Only students can register for contests",
                mapOf("reason" to "ROLE_NOT_ALLOWED"),
            )
        }
        if (!visibleTo(user, contest)) {
            throw ApiException(
                ApiErrorCode.FORBIDDEN,
                "This contest is not for your grade",
                mapOf("reason" to "GRADE_MISMATCH"),
            )
        }
        val subscription = subscriptionService.view(userId)
        val tier = SubscriptionTier.valueOf(subscription.tier)
        val status = SubscriptionStatus.valueOf(subscription.status)
        val entitled = user.isVerified && tier != SubscriptionTier.BASE && status == SubscriptionStatus.ACTIVE
        if (!entitled) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "An active EXPLORER or PRO subscription is required to join contests")
        }
        if (authoring.statusOf(contest) != ContestStatus.UPCOMING) {
            throw conflict("Contest registration is closed")
        }
        if (registrationRepository.findByStudentIdAndContestId(userId, contest.id) != null) {
            throw conflict("Already registered for this contest")
        }
        contest.maxParticipants?.let { max ->
            if (registrationRepository.countByContestId(contest.id) >= max) {
                throw conflict("Contest is full")
            }
        }
        val registration = ContestRegistrationEntity().apply {
            contestId = contest.id
            studentId = userId
            registeredAt = clock.instant()
        }
        registrationRepository.save(registration)
        return RegistrationResponse(success = true, message = "Registered", registrationId = registration.id.toString())
    }

    /**
     * The learner withdraws before the window opens. This is a real delete of the registration, not
     * a hidden flag, so capacity is returned to other students.
     */
    @Transactional
    fun withdraw(userId: UUID, contestIdRaw: String) {
        val contest = findPublished(contestIdRaw)
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        if (user.role != Role.STUDENT) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only students can withdraw from contests")
        }
        if (authoring.statusOf(contest) != ContestStatus.UPCOMING) {
            throw conflict("You can only withdraw before the contest starts")
        }
        registrationRepository.deleteByStudentIdAndContestId(userId, contest.id)
    }

    /** The learner's own durable result (survives beyond the submit screen). */
    @Transactional(readOnly = true)
    fun result(userId: UUID, contestIdRaw: String): com.afrithecus.brainbox.api.contests.web.ContestResultPayload {
        val contest = findPublished(contestIdRaw)
        val submission = submissionRepository.findByUserIdAndContestId(userId, contest.id)
            ?: throw notFound("No result for this contest")
        return submission.toResultPayload()
    }

    @Transactional(readOnly = true)
    fun leaderboard(userId: UUID, contestIdRaw: String): com.afrithecus.brainbox.api.contests.web.LeaderboardPayload {
        val contest = findPublished(contestIdRaw)
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        if (!visibleTo(user, contest)) throw notFound("Contest not found")
        val ranked = submissionRepository.findAllByContestId(contest.id)
            .sortedWith(
                compareByDescending<com.afrithecus.brainbox.api.contests.entity.ContestSubmissionEntity> { it.score }
                    .thenBy { it.submittedAt }
            )
        var userEntry: com.afrithecus.brainbox.api.contests.web.LeaderboardEntry? = null
        val entries = ranked.mapIndexed { index, submission ->
            val name = userRepository.findById(submission.userId).map { it.name }.orElse("Student")
            val entry = com.afrithecus.brainbox.api.contests.web.LeaderboardEntry(
                rank = index + 1,
                studentName = name,
                score = submission.score,
            )
            if (submission.userId == userId) userEntry = entry
            entry
        }
        return com.afrithecus.brainbox.api.contests.web.LeaderboardPayload(
            contestId = contest.id.toString(),
            entries = entries,
            userEntry = userEntry,
        )
    }

    // ------------------------------------------------------------ internals

    private fun published(): List<ContestEntity> =
        contestRepository.findAllByLifecycle(ContestLifecycle.PUBLISHED)

    /**
     * A contest is a grade-level resource. Global BrainBox contests and school contests are still
     * grade-filtered; SCHOOL/SCHOOL_GRADE additionally require the caller's school. If a contest has
     * no grade token it is treated as open (legacy rows), never as a different grade.
     */
    private fun visibleTo(user: com.afrithecus.brainbox.api.identity.entity.UserEntity, contest: ContestEntity): Boolean {
        val sameSchool = contest.scope == ContestScope.GLOBAL ||
            (user.schoolId != null && user.schoolId == contest.schoolId)
        if (!sameSchool) return false
        val contestGrade = contest.grade.trim()
        val userGrade = user.gradeLevel?.trim()
        if (contestGrade.isEmpty()) return true
        // A learner with no known grade cannot be proven eligible; do not leak a grade contest.
        if (userGrade.isNullOrEmpty()) return false
        return normaliseGrade(contestGrade) == normaliseGrade(userGrade)
    }

    private fun normaliseGrade(raw: String): String {
        val digits = Regex("\\d+").find(raw)?.value
        return digits ?: raw.trim().lowercase()
    }

    private fun com.afrithecus.brainbox.api.contests.entity.ContestSubmissionEntity.toResultPayload():
        com.afrithecus.brainbox.api.contests.web.ContestResultPayload {
        val answers = answers?.let { runCatching { mapper.readTree(it) }.getOrNull() }
        val questionResults = questionResults?.let {
            runCatching {
                mapper.readValue(it, Array<com.afrithecus.brainbox.api.exams.web.QuestionResultPayload>::class.java).toList()
            }.getOrNull()
        }.orEmpty()
        return com.afrithecus.brainbox.api.contests.web.ContestResultPayload(
            contestId = contestId.toString(),
            userId = userId.toString(),
            score = score,
            totalPoints = totalPoints,
            percentage = percentage,
            correctAnswers = correctCount,
            totalQuestions = questionCount,
            timeTakenSeconds = 0,
            submittedAt = submittedAt.toEpochMilli(),
            integrityScore = 100,
            integrityFlags = emptyList(),
            answers = answers,
            questionResults = questionResults,
        )
    }

    private fun findPublished(raw: String): ContestEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("contest id is not a valid identifier")
        val contest = contestRepository.findById(id).orElse(null)
            ?: throw notFound("Contest not found")
        if (contest.lifecycle != ContestLifecycle.PUBLISHED) throw notFound("Contest not found")
        return contest
    }

    private fun enrich(contest: ContestEntity, userId: UUID): ContestPayload {
        val base = authoring.toPayload(contest, includeQuestions = false, includeKeys = false)
        val registered = registrationRepository.findByStudentIdAndContestId(userId, contest.id) != null
        val submission = submissionRepository.findByUserIdAndContestId(userId, contest.id)
        return base.copy(
            isUserRegistered = registered,
            userScore = submission?.score,
            userRank = submission?.let {
                submissionRepository.countStrictlyBetter(contest.id, it.score).toInt() + 1
            },
        )
    }
}
