package com.afrithecus.brainbox.api.achievements

import com.afrithecus.brainbox.api.achievements.entity.UserAchievementsEntity
import com.afrithecus.brainbox.api.achievements.entity.UserBadgeEntity
import com.afrithecus.brainbox.api.achievements.entity.UserRewardEntity
import com.afrithecus.brainbox.api.achievements.entity.XpEventEntity
import com.afrithecus.brainbox.api.achievements.repository.BadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.RewardRepository
import com.afrithecus.brainbox.api.achievements.repository.UserAchievementsRepository
import com.afrithecus.brainbox.api.achievements.repository.UserBadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.UserRewardRepository
import com.afrithecus.brainbox.api.achievements.repository.XpEventRepository
import com.afrithecus.brainbox.api.achievements.web.BadgePayload
import com.afrithecus.brainbox.api.achievements.web.ContestHistoryEntryPayload
import com.afrithecus.brainbox.api.achievements.web.LeaderboardEntryPayload
import com.afrithecus.brainbox.api.achievements.web.LeaderboardResponsePayload
import com.afrithecus.brainbox.api.achievements.web.MasteryNodePayload
import com.afrithecus.brainbox.api.achievements.web.MasteryTreePayload
import com.afrithecus.brainbox.api.achievements.web.RewardItemPayload
import com.afrithecus.brainbox.api.achievements.web.ScholarshipFlagPayload
import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.career.repository.ScholarshipRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.mastery.MasteryService
import com.afrithecus.brainbox.api.mastery.model.MasteryLevel
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Achievements, XP, badges, leaderboards and the rewards store (doc 03 §7/§8).
 * XP is event-sourced (xp_events) so weekly leaderboards are server-computed;
 * streaks are derived from real activity dates.
 */
@Service
class AchievementsService(
    private val userAchievementsRepository: UserAchievementsRepository,
    private val xpEventRepository: XpEventRepository,
    private val badgeRepository: BadgeRepository,
    private val userBadgeRepository: UserBadgeRepository,
    private val rewardRepository: RewardRepository,
    private val userRewardRepository: UserRewardRepository,
    private val scholarshipRepository: ScholarshipRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val examSubmissionRepository: ExamSubmissionRepository,
    private val contestSubmissionRepository: ContestSubmissionRepository,
    private val contestRegistrationRepository: ContestRegistrationRepository,
    private val contestRepository: ContestRepository,
    private val topicMasteryRepository: TopicMasteryRepository,
    private val masteryService: MasteryService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun userAchievements(current: CurrentUser, userIdRaw: String): UserAchievementsPayload =
        build(requireSelf(current, userIdRaw))

    /** Staff-facing read used by the teacher student-analytics surface. */
    @Transactional(readOnly = true)
    fun forUser(userId: UUID): UserAchievementsPayload = build(userId)

    @Transactional(readOnly = true)
    fun contestHistory(current: CurrentUser, userIdRaw: String): List<ContestHistoryEntryPayload> {
        val userId = requireSelf(current, userIdRaw)
        return contestSubmissionRepository.findAllByUserId(userId).sortedByDescending { it.submittedAt }
            .mapNotNull { submission ->
                val contest = contestRepository.findById(submission.contestId).orElse(null) ?: return@mapNotNull null
                val rank = (contestSubmissionRepository.countStrictlyBetter(contest.id, submission.score) + 1).toInt()
                val registered = contestRegistrationRepository.countByContestId(contest.id).toInt()
                val participants = if (registered > 0) registered else contestSubmissionRepository.findAllByContestId(contest.id).size
                ContestHistoryEntryPayload(
                    contestId = contest.id.toString(),
                    contestTitle = contest.title,
                    subject = contest.subject,
                    yourScore = submission.percentage.toDouble(),
                    totalScore = 100.0,
                    rank = rank,
                    totalParticipants = participants,
                    date = submission.submittedAt.toEpochMilli(),
                    prizeWon = if (rank <= 3) contest.prize else null,
                )
            }
    }

    @Transactional(readOnly = true)
    fun leaderboard(current: CurrentUser, type: String, limit: Int): LeaderboardResponsePayload {
        val capped = limit.coerceIn(1, 200)
        val page = PageRequest.of(0, capped)
        return when (type.trim().lowercase()) {
            "weekly" -> {
                val totals = xpEventRepository.weeklyTotals(clock.instant().minus(Duration.ofDays(7)), page)
                val users = userRepository.findAllById(totals.map { it.getUserId() }).associateBy { it.id }
                val entries = totals.mapIndexed { index, total ->
                    val user = users[total.getUserId()]
                    LeaderboardEntryPayload(
                        userId = total.getUserId().toString(),
                        rank = index + 1,
                        studentName = user?.name ?: "Student",
                        schoolName = schoolNameOf(user),
                        score = total.getTotal().toDouble(),
                        isCurrentUser = total.getUserId() == current.userId,
                    )
                }
                LeaderboardResponsePayload(entries, entries.firstOrNull { it.isCurrentUser })
            }
            "school" -> {
                val schoolId = userRepository.findById(current.userId).map { it.schoolId }.orElse(null)
                val rows = if (schoolId == null) {
                    userAchievementsRepository.findAllByOrderByTotalXpDesc(page)
                } else {
                    userAchievementsRepository.topBySchool(schoolId, page)
                }
                leaderboardFromRows(rows, current)
            }
            else -> leaderboardFromRows(userAchievementsRepository.findAllByOrderByTotalXpDesc(page), current)
        }
    }

    @Transactional(readOnly = true)
    fun masteryTree(current: CurrentUser, userIdRaw: String): MasteryTreePayload {
        val userId = requireSelf(current, userIdRaw)
        val rows = topicMasteryRepository.findAllByUserIdOrderByScoreAsc(userId)
        if (rows.isEmpty()) return MasteryTreePayload(emptyList(), "")
        val nextBySubject = rows.groupBy { it.subject }.mapValues { (_, list) -> list.first() }
        val root = MasteryNodePayload(
            nodeId = "root",
            name = "Overall Mastery",
            masteryPercent = masteryService.overallScore(userId).toFloat(),
            isUnlocked = true,
            subject = "",
        )
        val nodes = rows.map { row ->
            MasteryNodePayload(
                nodeId = nodeId(row.topicId),
                name = row.topicName,
                masteryPercent = row.score.toFloat(),
                isUnlocked = row.score > 0.0,
                prerequisites = listOf("root"),
                subject = row.subject,
                recommendedNextTopic = nextBySubject[row.subject]?.takeIf { it.topicId != row.topicId }?.topicId?.let(::nodeId),
            )
        }
        return MasteryTreePayload(
            nodes = listOf(root) + nodes,
            rootNodeId = "root",
            recommendedPath = rows.filter { it.score < 50.0 }.map { nodeId(it.topicId) },
        )
    }

    @Transactional(readOnly = true)
    fun rewards(current: CurrentUser): List<RewardItemPayload> {
        val redeemed = userRewardRepository.findAllByUserId(current.userId).associateBy { it.rewardId }
        return rewardRepository.findAllByOrderByXpCostAsc().map { reward ->
            RewardItemPayload(
                id = reward.id.toString(),
                title = reward.title,
                description = reward.description,
                xpCost = reward.xpCost,
                icon = reward.icon,
                type = reward.type,
                isRedeemed = redeemed.containsKey(reward.id),
                couponCode = redeemed[reward.id]?.couponCode,
            )
        }
    }

    @Transactional
    fun redeem(current: CurrentUser, userIdRaw: String, rewardIdRaw: String): RewardItemPayload {
        val userId = requireSelf(current, userIdRaw)
        val rewardId = parseUuid(rewardIdRaw, "reward id")
        val reward = rewardRepository.findById(rewardId).orElse(null) ?: throw notFound("Reward not found")
        if (!reward.available) throw conflict("Reward is not available")
        reward.validUntil?.let { if (it.isBefore(clock.instant())) throw conflict("Reward has expired") }
        if (userRewardRepository.findByUserIdAndRewardId(userId, rewardId) != null) {
            throw conflict("Reward already redeemed")
        }
        val row = ensure(userId)
        if (row.totalXp < reward.xpCost) throw conflict("Not enough XP to redeem this reward")
        row.totalXp -= reward.xpCost
        userAchievementsRepository.save(row)
        val redemption = userRewardRepository.save(UserRewardEntity().apply {
            this.userId = userId
            this.rewardId = rewardId
            couponCode = "BB-" + (1000 + SECURE_RANDOM.nextInt(9000))
        })
        return RewardItemPayload(
            id = reward.id.toString(),
            title = reward.title,
            description = reward.description,
            xpCost = reward.xpCost,
            icon = reward.icon,
            type = reward.type,
            isRedeemed = true,
            couponCode = redemption.couponCode,
        )
    }

    @Transactional
    fun awardXp(current: CurrentUser, userIdRaw: String, amount: Int, activityType: String): UserAchievementsPayload {
        val userId = requireSelf(current, userIdRaw)
        if (amount <= 0) throw invalidArgument("XP amount must be positive")
        val row = ensure(userId)
        row.totalXp += amount
        userAchievementsRepository.save(row)
        xpEventRepository.save(XpEventEntity().apply {
            this.userId = userId
            this.amount = amount
            this.activityType = activityType.trim().ifBlank { "GENERAL" }.uppercase()
        })
        return build(userId)
    }

    @Transactional
    fun unlockBadge(current: CurrentUser, userIdRaw: String, badgeIdRaw: String): UserAchievementsPayload {
        val userId = requireSelf(current, userIdRaw)
        val badgeId = parseUuid(badgeIdRaw, "badge id")
        if (!badgeRepository.existsById(badgeId)) throw notFound("Badge not found")
        if (userBadgeRepository.findByUserIdAndBadgeId(userId, badgeId) == null) {
            userBadgeRepository.save(UserBadgeEntity().apply {
                this.userId = userId
                this.badgeId = badgeId
            })
        }
        return build(userId)
    }

    // ------------------------------------------------------------ internals

    private fun build(userId: UUID): UserAchievementsPayload {
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        val row = ensure(userId)
        val xp = row.totalXp
        val level = xp / LEVEL_XP + 1
        val earned = userBadgeRepository.findAllByUserId(userId).map { it.badgeId }.toSet()
        val badges = badgeRepository.findAll().map { badge ->
            BadgePayload(
                id = badge.id.toString(),
                title = badge.title,
                icon = badge.icon,
                isUnlocked = badge.id in earned,
                tier = badge.tier,
                cbcStrand = badge.cbcStrand,
            )
        }.sortedByDescending { it.isUnlocked }
        val dates = activityDates(userId)
        val rank = (userAchievementsRepository.countByTotalXpGreaterThan(xp) + 1).toInt()
        val total = userAchievementsRepository.count().coerceAtLeast(1).toInt()
        val contestsParticipated = contestRegistrationRepository.findAllByStudentId(userId).size
        val contestsWon = contestSubmissionRepository.findAllByUserId(userId)
            .count { contestSubmissionRepository.countStrictlyBetter(it.contestId, it.score) == 0L }
        val overall = masteryService.overallScore(userId)
        return UserAchievementsPayload(
            userId = userId.toString(),
            level = level,
            levelTitle = levelTitle(level),
            currentXP = xp % LEVEL_XP,
            xpToNextLevel = LEVEL_XP,
            totalXP = xp,
            currentStreak = currentStreak(dates),
            longestStreak = longestStreak(dates),
            badges = badges,
            completedChallenges = 0,
            totalChallenges = 0,
            contestsParticipated = contestsParticipated,
            contestsWon = contestsWon,
            rankPosition = rank,
            totalStudentsRanked = total,
            topPercentile = Math.round(1000.0 * (1.0 - (rank - 1).toDouble() / total)) / 10.0,
            masteryLevel = MasteryLevel.fromScore(overall).name,
            streakFreezes = row.streakFreezes,
            graceDays = row.graceDays,
            scholarshipFlags = scholarshipFlags(overall),
        )
    }

    private fun scholarshipFlags(overallMastery: Double): List<ScholarshipFlagPayload> {
        val progress = (overallMastery / 90.0).coerceIn(0.0, 1.0).toFloat()
        return scholarshipRepository.findAll()
            .filter { it.deadline.isAfter(clock.instant()) }
            .sortedBy { it.deadline }
            .take(5)
            .map { scholarship ->
                ScholarshipFlagPayload(
                    id = scholarship.id.toString(),
                    name = scholarship.title,
                    description = scholarship.amount,
                    criteria = scholarship.eligibilityLabel,
                    progress = progress,
                    isEligible = progress >= 1f,
                    organization = scholarship.provider,
                    deadline = scholarship.deadline.toEpochMilli(),
                )
            }
    }

    private fun leaderboardFromRows(
        rows: List<UserAchievementsEntity>,
        current: CurrentUser,
    ): LeaderboardResponsePayload {
        val users = userRepository.findAllById(rows.map { it.userId }).associateBy { it.id }
        val entries = rows.mapIndexed { index, row ->
            val user = users[row.userId]
            LeaderboardEntryPayload(
                userId = row.userId.toString(),
                rank = index + 1,
                studentName = user?.name ?: "Student",
                schoolName = schoolNameOf(user),
                score = row.totalXp.toDouble(),
                isCurrentUser = row.userId == current.userId,
            )
        }
        return LeaderboardResponsePayload(entries, entries.firstOrNull { it.isCurrentUser })
    }

    private fun schoolNameOf(user: UserEntity?): String {
        val schoolId = user?.schoolId ?: return ""
        return schoolRepository.findById(schoolId).map { it.name }.orElse("")
    }

    private fun ensure(userId: UUID): UserAchievementsEntity =
        userAchievementsRepository.findByUserId(userId) ?: userAchievementsRepository.save(
            UserAchievementsEntity().apply { this.userId = userId }
        )

    private fun activityDates(userId: UUID): Set<LocalDate> {
        val dates = linkedSetOf<LocalDate>()
        examSubmissionRepository.findAllByUserId(userId).forEach { dates += LocalDate.ofInstant(it.submittedAt, clock.zone) }
        contestSubmissionRepository.findAllByUserId(userId).forEach { dates += LocalDate.ofInstant(it.submittedAt, clock.zone) }
        topicMasteryRepository.findAllByUserIdOrderByScoreAsc(userId).forEach { dates += LocalDate.ofInstant(it.lastPracticed, clock.zone) }
        return dates
    }

    private fun currentStreak(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        val today = LocalDate.now(clock)
        var cursor = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (cursor in dates) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private fun longestStreak(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.sorted()
        var longest = 1
        var run = 1
        for (index in 1 until sorted.size) {
            run = if (sorted[index - 1].plusDays(1) == sorted[index]) run + 1 else 1
            if (run > longest) longest = run
        }
        return longest
    }

    private fun levelTitle(level: Int): String =
        LEVEL_TITLES[(level - 1).coerceIn(0, LEVEL_TITLES.size - 1)]

    private fun nodeId(topicId: String): String = "topic-" + topicId

    private fun requireSelf(current: CurrentUser, userIdRaw: String): UUID {
        if (userIdRaw != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's achievements")
        }
        return current.userId
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument(label + " is not a valid identifier")

    private companion object {
        const val LEVEL_XP = 1000
        val SECURE_RANDOM = SecureRandom()
        val LEVEL_TITLES = listOf(
            "Novice", "Learner", "Explorer", "Achiever", "Scholar",
            "Strategist", "Alchemist", "Sage", "Master", "Legend",
        )
    }
}
