package com.afrithecus.brainbox.api.user

import com.afrithecus.brainbox.api.achievements.AchievementsService
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import com.afrithecus.brainbox.api.subscription.SubscriptionView
import com.afrithecus.brainbox.api.user.web.UserProgressPayload
import com.afrithecus.brainbox.api.user.web.UserProfilePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Dashboard support endpoints (doc 14 §3): the caller's profile (with subscription
 * banner status) and progress (XP/level/streak/badges). Self-only; the level
 * convention matches AchievementsService (level = totalXP / 500 + 1) so the XP ring
 * does not drift.
 */
@Service
class UserProfileService(
    private val userRepository: UserRepository,
    private val subscriptionService: SubscriptionService,
    private val achievementsService: AchievementsService,
    private val examSubmissionRepository: ExamSubmissionRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun profile(current: CurrentUser, userIdRaw: String): UserProfilePayload {
        val userId = requireSelf(current, userIdRaw)
        val user = userRepository.findById(userId).orElse(null) ?: throw notFound("User not found")
        val subscription = subscriptionService.view(userId)
        return UserProfilePayload(
            id = user.id.toString(),
            name = user.name,
            email = user.email.orEmpty(),
            avatarUrl = "",
            subscriptionStatus = subscriptionStatus(subscription, clock.instant()),
            subscriptionExpiry = subscription.expiryDate?.toString().orEmpty(),
            grade = GradeNormalizer.canonicalKey(user.gradeLevel) ?: DEFAULT_GRADE,
        )
    }

    @Transactional(readOnly = true)
    fun progress(current: CurrentUser, userIdRaw: String): UserProgressPayload {
        val userId = requireSelf(current, userIdRaw)
        val achievements = achievementsService.userAchievements(current, userIdRaw)
        val trend = examSubmissionRepository.findAllByUserId(userId)
            .sortedBy { it.submittedAt }
            .takeLast(TREND_POINTS)
            .map { it.percentage.toFloat() }
        return UserProgressPayload(
            userId = userId.toString(),
            xp = achievements.totalXP,
            level = achievements.level,
            streakDays = achievements.currentStreak,
            badges = achievements.badges.filter { it.isUnlocked }.map { it.title },
            progressTrend = trend,
            trendPercentage = if (trend.size >= 2) (trend.last() - trend.first()).roundToInt() else 0,
        )
    }

    /** Client subscription banners: Active / Expiring (<=14d) / Expired. */
    private fun subscriptionStatus(view: SubscriptionView, now: Instant): String = when {
        view.status == "EXPIRED" || view.tier == "BASE" || view.status == "NONE" -> "Expired"
        view.expiryDate != null && Duration.between(now, Instant.ofEpochMilli(view.expiryDate)).toDays() <= EXPIRING_DAYS -> "Expiring"
        else -> "Active"
    }

    private fun requireSelf(current: CurrentUser, userIdRaw: String): UUID {
        val id = runCatching { UUID.fromString(userIdRaw) }.getOrNull()
            ?: throw invalidArgument("user id is not a valid identifier")
        if (id != current.userId && current.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's profile")
        }
        return id
    }

    private companion object {
        const val DEFAULT_GRADE = 9
        const val TREND_POINTS = 7
        const val EXPIRING_DAYS = 14L
    }
}
