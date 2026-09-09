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
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun listByWindow(userId: UUID, status: ContestStatus): List<ContestPayload> =
        published().filter { authoring.statusOf(it) == status }
            .sortedBy { it.startTime }
            .map { enrich(it, userId) }

    @Transactional(readOnly = true)
    fun detail(userId: UUID, contestIdRaw: String): ContestPayload {
        val contest = findPublished(contestIdRaw)
        val base = authoring.toPayload(contest, includeQuestions = false, includeKeys = false)
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
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
            throw ApiException(ApiErrorCode.FORBIDDEN, "Only students can register for contests")
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

    // ------------------------------------------------------------ internals

    private fun published(): List<ContestEntity> =
        contestRepository.findAllByLifecycle(ContestLifecycle.PUBLISHED)

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
