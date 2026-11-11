package com.afrithecus.brainbox.api.achievements

import com.afrithecus.brainbox.api.achievements.entity.StreakCreditEntity
import com.afrithecus.brainbox.api.achievements.entity.UserAchievementsEntity
import com.afrithecus.brainbox.api.achievements.entity.UserBadgeEntity
import com.afrithecus.brainbox.api.achievements.entity.UserRewardEntity
import com.afrithecus.brainbox.api.achievements.entity.XpEventEntity
import com.afrithecus.brainbox.api.achievements.repository.BadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.RewardRepository
import com.afrithecus.brainbox.api.achievements.repository.StreakCreditRepository
import com.afrithecus.brainbox.api.achievements.repository.UserAchievementsRepository
import com.afrithecus.brainbox.api.achievements.repository.UserBadgeRepository
import com.afrithecus.brainbox.api.achievements.repository.UserRewardRepository
import com.afrithecus.brainbox.api.achievements.repository.XpEventRepository
import com.afrithecus.brainbox.api.achievements.web.BadgePayload
import com.afrithecus.brainbox.api.achievements.web.ContestHistoryEntryPayload
import com.afrithecus.brainbox.api.achievements.web.DailyActivityPayload
import com.afrithecus.brainbox.api.achievements.web.LeaderboardEntryPayload
import com.afrithecus.brainbox.api.achievements.web.LeaderboardResponsePayload
import com.afrithecus.brainbox.api.achievements.web.MasteryNodePayload
import com.afrithecus.brainbox.api.achievements.web.MasteryTreePayload
import com.afrithecus.brainbox.api.achievements.web.MonthlyDigestPayload
import com.afrithecus.brainbox.api.achievements.web.RewardItemPayload
import com.afrithecus.brainbox.api.achievements.web.ScholarshipFlagPayload
import com.afrithecus.brainbox.api.achievements.web.StreakAdjustmentRequest
import com.afrithecus.brainbox.api.achievements.web.StreakCreditPayload
import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.career.repository.ScholarshipRepository
import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.doubt.repository.DoubtAnswerRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.repository.LearningProgressRepository
import com.afrithecus.brainbox.api.mastery.MasteryService
import com.afrithecus.brainbox.api.mastery.model.MasteryLevel
import com.afrithecus.brainbox.api.mastery.repository.TopicMasteryRepository
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Achievements, XP, badges, leaderboards and the rewards store (doc 03 §7/§8).
 * XP is event-sourced (xp_events) so weekly leaderboards are server-computed;
 * streaks are derived from real activity dates plus persisted freeze/grace credits.
 *
 * F5-A1: badge criteria are server-owned. [awardEligibleBadges] is called by the
 * scheduled sweeper and after an XP award; the client never decides a badge.
 */
@Service
class AchievementsService(
    private val userAchievementsRepository: UserAchievementsRepository,
    private val xpEventRepository: XpEventRepository,
    private val badgeRepository: BadgeRepository,
    private val userBadgeRepository: UserBadgeRepository,
    private val rewardRepository: RewardRepository,
    private val userRewardRepository: UserRewardRepository,
    private val streakCreditRepository: StreakCreditRepository,
    private val scholarshipRepository: ScholarshipRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val examRepository: ExamRepository,
    private val examSubmissionRepository: ExamSubmissionRepository,
    private val contestSubmissionRepository: ContestSubmissionRepository,
    private val contestRegistrationRepository: ContestRegistrationRepository,
    private val contestRepository: ContestRepository,
    private val doubtAnswerRepository: DoubtAnswerRepository,
    private val cbcProjectRepository: CbcProjectRepository,
    private val learningProgressRepository: LearningProgressRepository,
    private val topicMasteryRepository: TopicMasteryRepository,
    private val masteryService: MasteryService,
    private val notificationService: NotificationService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun userAchievements(current: CurrentUser, userIdRaw: String): UserAchievementsPayload =
        build(requireSelf(current, userIdRaw))

    /** Staff-facing read used by the teacher student-analytics surface. */
    @Transactional(readOnly = true)
    fun forUser(userId: UUID): UserAchievementsPayload = build(userId)

    @Transactional(readOnly = true)
    fun contestHistory(current: CurrentUser, userIdRaw: String): List<ContestHistoryEntryPayload> =
        contestHistoryForUser(requireSelf(current, userIdRaw))

    /** Staff-facing read: a class teacher/coordinator can see a learner's contest history. */
    @Transactional(readOnly = true)
    fun contestHistoryForUser(userId: UUID): List<ContestHistoryEntryPayload> {
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
        awardEligibleBadges(userId)
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

    /**
     * Buy or spend a streak freeze/grace day (F5-A3). A credit covers exactly one
     * missed local day; the streak derivation treats that day as active. Buying uses
     * the learner's XP balance but does not create a negative XP event, so weekly
     * leaderboard totals stay "earned this week" rather than falling after a purchase.
     */
    @Transactional
    fun adjustStreak(current: CurrentUser, userIdRaw: String, request: StreakAdjustmentRequest): UserAchievementsPayload {
        val userId = requireSelf(current, userIdRaw)
        val row = ensure(userId)
        val action = request.action.trim().uppercase()
        val kind = when (request.kind.trim().uppercase()) {
            "FREEZE" -> "FREEZE"
            "GRACE" -> "GRACE"
            else -> throw invalidArgument("kind must be FREEZE or GRACE")
        }
        when (action) {
            "BUY" -> {
                val (cost, cap) = if (kind == "FREEZE") FREEZE_COST to MAX_FREEZES else GRACE_COST to MAX_GRACE_DAYS
                val owned = if (kind == "FREEZE") row.streakFreezes else row.graceDays
                if (owned >= cap) throw conflict("You already hold the maximum number of " + kind.lowercase() + " days")
                if (row.totalXp < cost) throw conflict("Not enough XP to buy this streak credit")
                row.totalXp -= cost
                if (kind == "FREEZE") row.streakFreezes += 1 else row.graceDays += 1
                userAchievementsRepository.save(row)
            }
            "USE" -> {
                val today = LocalDate.now(clock)
                val date = request.date?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull() ?: throw invalidArgument("date must be ISO yyyy-MM-dd")
                } ?: today.minusDays(1)
                if (date.isAfter(today.minusDays(1))) throw invalidArgument("A credit can only cover a completed day")
                if (date.isBefore(today.minusDays(MAX_CREDIT_LOOKBACK_DAYS))) {
                    throw invalidArgument("A credit can only cover the last " + MAX_CREDIT_LOOKBACK_DAYS + " days")
                }
                if (date in activityDates(userId)) throw conflict("That day already counts as activity")
                if (streakCreditRepository.findByUserIdAndCreditDate(userId, date) != null) {
                    throw conflict("That day is already covered by a streak credit")
                }
                val owned = if (kind == "FREEZE") row.streakFreezes else row.graceDays
                if (owned <= 0) throw conflict("No " + kind.lowercase() + " days left")
                if (kind == "FREEZE") row.streakFreezes -= 1 else row.graceDays -= 1
                userAchievementsRepository.save(row)
                streakCreditRepository.save(StreakCreditEntity().apply {
                    this.userId = userId
                    this.creditDate = date
                    this.kind = kind
                })
            }
            else -> throw invalidArgument("action must be BUY or USE")
        }
        return build(userId)
    }

    // ------------------------------------------------------------ badge awarding

    /**
     * Awards every badge whose rule the learner now satisfies. Safe to call repeatedly:
     * a badge already in user_badges is never inserted twice. Returns the newly awarded
     * badges and sends one in-app notification per badge.
     */
    @Transactional
    fun awardEligibleBadges(userId: UUID): List<BadgePayload> {
        val user = userRepository.findById(userId).orElse(null) ?: return emptyList()
        if (user.role != Role.STUDENT) return emptyList()
        val earned = userBadgeRepository.findAllByUserId(userId).map { it.badgeId }.toSet()
        val dates = activityDates(userId)
        val submissions = examSubmissionRepository.findAllByUserId(userId)
        val submissionsEightyPlus = submissions.count { it.percentage >= 80 }
        val earlyBird = submissions.any { submission ->
            java.time.LocalTime.ofInstant(submission.submittedAt, clock.zone).hour < 8
        }
        val examDurations = examRepository.findAllById(submissions.map { it.examId }).associateBy { it.id }
        val speedDemon = submissions.any { submission ->
            val duration = examDurations[submission.examId]?.durationMinutes ?: 0
            duration > 0 && submission.timeTakenSeconds > 0 &&
                submission.timeTakenSeconds * 2 <= duration * 60
        }
        val answers = doubtAnswerRepository.findAllByAuthorId(userId)
        val completedPosts = learningProgressRepository.findAllByUserId(userId).count { it.completed }
        val projectApproved = cbcProjectRepository.findAllByStudentIdOrderByCreatedAtDesc(userId)
            .any { it.status == ProjectStatus.APPROVED || it.status == ProjectStatus.FEATURED }
        val submissionsForContest = contestSubmissionRepository.findAllByUserId(userId)
        val contestsWon = submissionsForContest.count {
            contestSubmissionRepository.countStrictlyBetter(it.contestId, it.score) == 0L
        }
        val xpRow = userAchievementsRepository.findByUserId(userId)
        val rank = xpRow?.let { (userAchievementsRepository.countByTotalXpGreaterThan(it.totalXp) + 1).toInt() } ?: 0
        val totalRanked = userAchievementsRepository.count().coerceAtLeast(1).toInt()
        val newlyAwarded = mutableListOf<BadgePayload>()
        for (badge in badgeRepository.findAll()) {
            if (badge.id in earned) continue
            val earnedNow = when (badge.title) {
                "Early Bird" -> earlyBird
                "Quiz Master" -> submissionsEightyPlus >= 5
                "Speed Demon" -> speedDemon
                "Collaboration Star" -> answers.any { it.isAccepted }
                "Creative Thinker" -> projectApproved
                "Digital Explorer" -> completedPosts >= 10
                "Helper" -> answers.size >= 5
                "Contest Winner" -> contestsWon >= 1
                "Perfect Week" -> longestStreak(dates) >= 7
                // A percentile needs a population; with fewer than ten ranked learners
                // every learner would otherwise receive it on their first XP award.
                "Top 10%" -> rank > 0 && totalRanked >= 10 &&
                    rank <= ceil(totalRanked * 0.10).toInt().coerceAtLeast(1)
                else -> false
            }
            if (!earnedNow) continue
            userBadgeRepository.save(UserBadgeEntity().apply {
                this.userId = userId
                this.badgeId = badge.id
            })
            notificationService.notifyUser(
                userId = userId,
                title = "Badge unlocked: " + badge.title,
                message = "You earned the " + badge.title + " badge. Open Achievements to see it.",
                type = NotificationType.REWARD,
                urgency = NotificationUrgency.LOW,
                priority = NotificationPriority.NORMAL,
                actionRoute = "achievements",
                actionLabel = "View achievements",
                metadata = mapOf("badgeId" to badge.id.toString()),
            )
            newlyAwarded += BadgePayload(
                id = badge.id.toString(),
                title = badge.title,
                icon = badge.icon,
                isUnlocked = true,
                tier = badge.tier,
                cbcStrand = badge.cbcStrand,
            )
        }
        return newlyAwarded
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
        val credits = streakCreditRepository.findAllByUserIdOrderByCreditDateAsc(userId)
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
            contestsParticipated = contestsParticipated,
            contestsWon = contestsWon,
            rankPosition = rank,
            totalStudentsRanked = total,
            topPercentile = Math.round(1000.0 * (1.0 - (rank - 1).toDouble() / total)) / 10.0,
            masteryLevel = MasteryLevel.fromScore(overall).name,
            streakFreezes = row.streakFreezes,
            graceDays = row.graceDays,
            streakCredits = credits.map { StreakCreditPayload(millisOf(it.creditDate), it.kind) },
            dailyActivity = dailyActivity(userId, dates),
            monthlyDigest = monthlyDigest(userId, dates),
            scholarshipFlags = scholarshipFlags(overall),
        )
    }

    private fun dailyActivity(userId: UUID, dates: Set<LocalDate>): List<DailyActivityPayload> {
        val today = LocalDate.now(clock)
        val start = today.minusDays(6)
        val events = xpEventRepository.findAllByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            userId,
            start.atStartOfDay(clock.zone).toInstant(),
        )
        val xpByDay = events.groupBy { LocalDate.ofInstant(it.createdAt, clock.zone) }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
        return (0..6).map { offset ->
            val day = start.plusDays(offset.toLong())
            DailyActivityPayload(
                date = millisOf(day),
                active = day in dates,
                xpEarned = xpByDay[day] ?: 0,
            )
        }
    }

    private fun monthlyDigest(userId: UUID, dates: Set<LocalDate>): MonthlyDigestPayload {
        val today = LocalDate.now(clock)
        val monthStart = today.withDayOfMonth(1)
        val monthStartInstant = monthStart.atStartOfDay(clock.zone).toInstant()
        val events = xpEventRepository.findAllByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, monthStartInstant)
        val xpGained = events.sumOf { it.amount }
        val monthMillis = monthStartInstant.toEpochMilli()
        val contestsWon = contestHistoryForUser(userId).count { it.date >= monthMillis && it.rank == 1 }
        val activeDays = dates.count { !it.isBefore(monthStart) && !it.isAfter(today) }
        val strongest = topicMasteryRepository.findAllByUserIdOrderByScoreAsc(userId)
            .maxByOrNull { it.score }?.subject?.takeIf { it.isNotBlank() }
        val recommendation = when {
            xpGained <= 0 && activeDays == 0 -> "Start with one short practice session this week to build momentum."
            currentStreak(dates) >= 7 -> "Your streak is strong. Keep one short session a day to protect it."
            strongest != null -> "Your strongest subject is " + strongest + ". Spend one session on a weaker topic this week."
            else -> "Aim for three active days this week; consistency beats one long session."
        }
        return MonthlyDigestPayload(
            month = YearMonth.from(today).toString(),
            xpGained = xpGained,
            contestsWon = contestsWon,
            strongestSubject = strongest,
            activeDays = activeDays,
            recommendation = recommendation,
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
                    applicationUrl = scholarship.externalUrl.takeIf { it.isNotBlank() },
                    // No scholarship_applications table exists yet; null is honest and the
                    // client must not render a fabricated "not applied" state.
                    applicationStatus = null,
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
        xpEventRepository.findAllByUserId(userId).forEach { dates += LocalDate.ofInstant(it.createdAt, clock.zone) }
        streakCreditRepository.findAllByUserIdOrderByCreditDateAsc(userId).forEach { dates += it.creditDate }
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

    private fun millisOf(day: LocalDate): Long = day.atStartOfDay(clock.zone).toInstant().toEpochMilli()

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
        const val FREEZE_COST = 300
        const val GRACE_COST = 600
        const val MAX_FREEZES = 5
        const val MAX_GRACE_DAYS = 3
        const val MAX_CREDIT_LOOKBACK_DAYS = 30L
        val SECURE_RANDOM = SecureRandom()
        val LEVEL_TITLES = listOf(
            "Novice", "Learner", "Explorer", "Achiever", "Scholar",
            "Strategist", "Alchemist", "Sage", "Master", "Legend",
        )
    }
}
