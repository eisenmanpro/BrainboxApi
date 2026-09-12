package com.afrithecus.brainbox.api.dashboard

import com.afrithecus.brainbox.api.analytics.AnalyticsService
import com.afrithecus.brainbox.api.analytics.web.StudentPerformancePayload
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.repository.ContestRepository
import com.afrithecus.brainbox.api.contests.repository.ContestSubmissionRepository
import com.afrithecus.brainbox.api.dashboard.web.AssignmentPayload
import com.afrithecus.brainbox.api.dashboard.web.ContestPayload
import com.afrithecus.brainbox.api.dashboard.web.DashboardInsightsPayload
import com.afrithecus.brainbox.api.dashboard.web.FeaturedContestPayload
import com.afrithecus.brainbox.api.dashboard.web.FocusZonePayload
import com.afrithecus.brainbox.api.dashboard.web.NationalPulsePayload
import com.afrithecus.brainbox.api.dashboard.web.PeerComparisonPayload
import com.afrithecus.brainbox.api.dashboard.web.QuickActionPayload
import com.afrithecus.brainbox.api.dashboard.web.RecentActivityPayload
import com.afrithecus.brainbox.api.dashboard.web.StreakInfoPayload
import com.afrithecus.brainbox.api.dashboard.web.TeacherShoutoutPayload
import com.afrithecus.brainbox.api.dashboard.web.WeeklyChallengePayload
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Student dashboard (BACKEND_BLUEPRINT §2). Assignments, contest cards, quick
 * actions and the insight panel are all derived server-side from live data; the
 * client renders them read-only.
 */
@Service
class DashboardService(
    private val homeworkRepository: HomeworkRepository,
    private val homeworkSubmissionRepository: HomeworkSubmissionRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val examRepository: ExamRepository,
    private val examSubmissionRepository: ExamSubmissionRepository,
    private val contestRepository: ContestRepository,
    private val contestRegistrationRepository: ContestRegistrationRepository,
    private val contestSubmissionRepository: ContestSubmissionRepository,
    private val userRepository: UserRepository,
    private val schoolRepository: SchoolRepository,
    private val sessionRepository: UserSessionRepository,
    private val analyticsService: AnalyticsService,
    private val codec: QuestionCodec,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun assignments(student: UserEntity): List<AssignmentPayload> {
        requireStudent(student)
        val classIds = myClassIds(student.id)
        if (classIds.isEmpty()) return emptyList()
        val submitted = homeworkSubmissionRepository.findAllByStudentId(student.id).map { it.homeworkId }.toSet()
        val now = clock.instant()
        return homeworkRepository.findAllByIsDraftFalseAndIsActiveTrue()
            .filter { hw -> hw.classId in classIds && isAssigned(hw, student.id) && hw.id !in submitted }
            .sortedBy { it.dueDate }
            .take(10)
            .map { AssignmentPayload(it.id, it.subject, it.title, dueLabel(it.dueDate, now), it.dueDate.isBefore(now)) }
    }

    @Transactional(readOnly = true)
    fun contests(student: UserEntity): List<ContestPayload> {
        val now = clock.instant()
        return contestRepository.findAllByLifecycle(ContestLifecycle.PUBLISHED)
            .filter { contest -> contest.grade.isBlank() || GradeNormalizer.sameGrade(contest.grade, student.gradeLevel) }
            .map { toContestPayload(it, student, now) }
            .sortedWith(compareBy({ statusOrder(it.status) }, { it.startTime }))
            .take(20)
    }

    @Transactional(readOnly = true)
    fun insights(current: CurrentUser, student: UserEntity): DashboardInsightsPayload {
        requireStudent(student)
        val performance = analyticsService.studentPerformance(current, student.id.toString())
        val attempts = examSubmissionRepository.findAllByUserId(student.id)
        val now = clock.instant()
        val streak = streakDays(attempts.map { it.submittedAt })
        return DashboardInsightsPayload(
            focusZone = focusZone(performance),
            nationalPulse = nationalPulse(now),
            teacherShoutout = teacherShoutout(student),
            peerComparison = peerComparison(student, performance),
            streakInfo = streakInfo(streak),
            weeklyChallenge = weeklyChallenge(attempts, now),
            socialNotifications = socialNotifications(student, performance),
            featuredContest = featuredContest(student, now),
            recentActivity = recentActivity(student, attempts),
            allBadges = badges(streak, performance, attempts.size),
            xpMultiplier = if (streak >= 7) 2 else 1,
            xpMultiplierHours = if (streak >= 7) 2 else 0,
            xpMultiplierSource = when {
                streak >= 7 -> "7-day streak"
                streak >= 3 -> "3-day streak"
                else -> ""
            },
        )
    }

    @Transactional(readOnly = true)
    fun quickActions(): List<QuickActionPayload> = listOf(
        QuickActionPayload("1", "Daily Quiz", "FlashOn", "10 mins", "daily_quiz"),
        QuickActionPayload("2", "AI Coach", "AutoAwesome", "Interview", "mock_interviews"),
        QuickActionPayload("3", "Live Classes", "Live", "Join Now", "live_classes"),
        QuickActionPayload("4", "Practice Contest", "EmojiEvents", "5 mins", "contests"),
        QuickActionPayload("5", "Revision Notes", "School", "PDF", "revision_notes"),
    )

    // ------------------------------------------------------------ insights

    private fun focusZone(performance: StudentPerformancePayload): FocusZonePayload {
        val weakest = performance.subjects.minByOrNull { it.percentage }
            ?: return FocusZonePayload("", "Take an exam to unlock personalised insights", 0)
        return FocusZonePayload(
            strugglingSubject = weakest.subject,
            recommendation = "Revise " + weakest.subject,
            xpBonus = if (weakest.percentage < 65.0) 50 else 0,
        )
    }

    private fun nationalPulse(now: Instant): NationalPulsePayload {
        val online = sessionRepository.countActiveUsersSince(now.minus(Duration.ofMinutes(15))).toInt()
        val totalUsers = userRepository.count().coerceAtLeast(1).toInt()
        val counts = examSubmissionRepository.countsByExam()
        val exams = examRepository.findAllById(counts.map { it.getExamId() }).associateBy { it.id }
        val top = counts.groupBy { exams[it.getExamId()]?.subject }
            .filterKeys { it != null }
            .mapValues { (_, rows) -> rows.sumOf { it.getTotal() } }
            .maxByOrNull { it.value }?.key
        return NationalPulsePayload(
            onlineCount = online,
            topSubject = top ?: "",
            activePercentage = (online * 100 / totalUsers).coerceIn(0, 100),
        )
    }

    private fun teacherShoutout(student: UserEntity): TeacherShoutoutPayload {
        val latest = latestHomework(student) ?: return TeacherShoutoutPayload("", "", "", 0, 0, 0)
        val submissions = homeworkSubmissionRepository.findAllByHomeworkId(latest.id)
        val graded = submissions.mapNotNull { it.grade }
        val mine = submissions.firstOrNull { it.studentId == student.id }?.grade ?: 0
        return TeacherShoutoutPayload(
            teacherName = latest.teacherName,
            subject = latest.subject,
            dueDate = dueLabel(latest.dueDate, clock.instant()),
            classAvg = if (graded.isEmpty()) 0 else graded.average().roundToInt(),
            userLast = mine,
            sentAt = latest.updatedAt.toEpochMilli(),
        )
    }

    private fun peerComparison(student: UserEntity, performance: StudentPerformancePayload): PeerComparisonPayload {
        val schoolId = student.schoolId ?: return PeerComparisonPayload(0, "", 0)
        val peers = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            .filter { it.role == Role.STUDENT && it.id != student.id }
        if (peers.isEmpty()) return PeerComparisonPayload(0, "", 0)
        val peerIds = peers.map { it.id }
        val peerSubmissions = examSubmissionRepository.findAllByUserIdIn(peerIds)
        val averages = peers.mapNotNull { peer ->
            val scores = peerSubmissions.filter { it.userId == peer.id }.map { it.percentage }
            if (scores.isEmpty()) null else peer to scores.average()
        }
        if (averages.isEmpty()) return PeerComparisonPayload(0, "", 0)
        val mine = performance.overallPercentage
        val all = averages.map { it.second } + mine
        val percentile = (100.0 * all.count { it <= mine } / all.size).roundToInt().coerceIn(0, 100)
        val target = averages.filter { it.second > mine }.minByOrNull { it.second }
        return PeerComparisonPayload(
            percentile = percentile,
            targetPeer = target?.first?.name ?: "",
            pointsAhead = target?.let { (it.second - mine).roundToInt() } ?: 0,
        )
    }

    private fun streakInfo(streak: Int): StreakInfoPayload {
        val next = if (streak == 0) 3 else ((streak / 5) + 1) * 5
        return StreakInfoPayload(
            streakDays = streak,
            nextMilestone = next,
            milestoneBadge = when {
                streak >= 10 -> "Gold Badge"
                streak >= 5 -> "Silver Badge"
                else -> "Bronze Badge"
            },
        )
    }

    private fun weeklyChallenge(attempts: List<ExamSubmissionEntity>, now: Instant): WeeklyChallengePayload {
        val weekStart = LocalDate.now(clock).with(DayOfWeek.MONDAY).atStartOfDay(clock.zone).toInstant()
        val current = attempts.count { it.submittedAt >= weekStart }
        return WeeklyChallengePayload(
            title = "Complete 5 exams this week",
            current = current,
            target = 5,
            reward = "100 XP + Rare Badge",
        )
    }

    private fun socialNotifications(student: UserEntity, performance: StudentPerformancePayload): List<String> {
        val notes = mutableListOf<String>()
        val schoolId = student.schoolId
        if (schoolId != null) {
            val members = userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId)
            val names = members.associate { it.id to it.name }
            val submissions = examSubmissionRepository.findAllByUserIdIn(members.map { it.id })
                .sortedByDescending { it.submittedAt }
                .take(2)
            val exams = examRepository.findAllById(submissions.map { it.examId }).associateBy { it.id }
            for (submission in submissions) {
                val exam = exams[submission.examId] ?: continue
                val name = names[submission.userId] ?: continue
                notes += name + " scored " + submission.percentage + "% in " + exam.subject
            }
        }
        examRepository.findAllByStatus(ExamStatus.PUBLISHED)
            .maxByOrNull { it.createdAt }
            ?.let { notes += "New exam: " + it.title }
        contestRepository.findAllByLifecycle(ContestLifecycle.PUBLISHED)
            .filter { it.startTime.isAfter(clock.instant()) }
            .minByOrNull { it.startTime }
            ?.let { notes += it.title + " starts in " + countdown(clock.instant(), it.startTime) }
        if (performance.weaknesses.isNotEmpty()) {
            notes += "Focus suggestion: revise " + performance.weaknesses.first()
        }
        return notes.take(5)
    }

    private fun featuredContest(student: UserEntity, now: Instant): FeaturedContestPayload {
        val next = contestRepository.findAllByLifecycle(ContestLifecycle.PUBLISHED)
            .filter { it.startTime.isAfter(now) && (it.grade.isBlank() || GradeNormalizer.sameGrade(it.grade, student.gradeLevel)) }
            .minByOrNull { it.startTime }
            ?: return FeaturedContestPayload("", "", "")
        return FeaturedContestPayload(next.title, countdown(now, next.startTime), next.prize ?: "")
    }

    private fun recentActivity(student: UserEntity, attempts: List<ExamSubmissionEntity>): List<RecentActivityPayload> {
        val exams = examRepository.findAllById(attempts.map { it.examId }).associateBy { it.id }
        val activity = attempts.mapNotNull { submission ->
            val exam = exams[submission.examId] ?: return@mapNotNull null
            RecentActivityPayload(
                id = submission.id.toString(),
                title = exam.title,
                subtitle = "Scored " + submission.percentage + "%",
                type = "EXAM_SUBMITTED",
                timestamp = submission.submittedAt.toEpochMilli(),
                status = "Published",
            )
        }.toMutableList()
        val registrations = contestRegistrationRepository.findAll()
            .filter { it.studentId == student.id }
            .sortedByDescending { it.registeredAt }
            .take(5)
        val contests = contestRepository.findAllById(registrations.map { it.contestId }).associateBy { it.id }
        for (registration in registrations) {
            val contest = contests[registration.contestId] ?: continue
            activity += RecentActivityPayload(
                id = registration.id.toString(),
                title = contest.title,
                subtitle = "Contest registration",
                type = "CONTEST_JOINED",
                timestamp = registration.registeredAt.toEpochMilli(),
            )
        }
        return activity.sortedByDescending { it.timestamp }.take(10)
    }

    private fun badges(streak: Int, performance: StudentPerformancePayload, attempts: Int): List<String> {
        val earned = mutableListOf<String>()
        if (streak >= 3) earned += "Streak Starter"
        if (streak >= 7) earned += "Streak King"
        if (attempts >= 5) earned += "Quiz Explorer"
        if (performance.overallPercentage >= 80.0) earned += "High Achiever"
        if (performance.subjects.any { it.percentage >= 80.0 }) earned += "Subject Star"
        if (earned.isEmpty()) earned += "Newcomer"
        return earned
    }

    // ------------------------------------------------------------ helpers

    private fun toContestPayload(contest: ContestEntity, student: UserEntity, now: Instant): ContestPayload {
        val status = when {
            now.isBefore(contest.startTime) -> "UPCOMING"
            now.isAfter(contest.endTime) -> "COMPLETED"
            else -> "ONGOING"
        }
        val registration = contestRegistrationRepository.findByStudentIdAndContestId(student.id, contest.id)
        val submission = contestSubmissionRepository.findByUserIdAndContestId(student.id, contest.id)
        return ContestPayload(
            id = contest.id.toString(),
            title = contest.title,
            subject = contest.subject,
            grade = contest.grade,
            status = status,
            startTime = contest.startTime.toEpochMilli(),
            endTime = contest.endTime.toEpochMilli(),
            entryFee = contest.entryFee,
            prize = contest.prize ?: "",
            registeredCount = contestRegistrationRepository.countByContestId(contest.id).toInt(),
            maxParticipants = contest.maxParticipants ?: 0,
            currentParticipantCount = if (status == "ONGOING") contestRegistrationRepository.countByContestId(contest.id).toInt() else null,
            userRank = submission?.let { contestSubmissionRepository.countStrictlyBetter(contest.id, it.score).toInt() + 1 },
            userScore = submission?.percentage?.toDouble(),
            isUserRegistered = registration != null,
        )
    }

    private fun statusOrder(status: String): Int = when (status) {
        "ONGOING" -> 0
        "UPCOMING" -> 1
        else -> 2
    }

    private fun countdown(now: Instant, target: Instant): String {
        val duration = Duration.between(now, target).coerceAtLeast(Duration.ZERO)
        return "%02dd : %02dh : %02dm".format(duration.toDays(), duration.toHours() % 24, duration.toMinutes() % 60)
    }

    private fun requireStudent(user: UserEntity) {
        if (user.role != Role.STUDENT) throw ApiException(ApiErrorCode.FORBIDDEN, "Student access only")
    }

    private fun myClassIds(studentId: java.util.UUID): Set<java.util.UUID> =
        membershipRepository.findAllByStudentId(studentId).map { it.classId }.toSet()

    private fun isAssigned(hw: HomeworkEntity, studentId: java.util.UUID): Boolean {
        val targeted = codec.parseList(hw.assignedStudentIds)
        return targeted.isNullOrEmpty() || targeted.contains(studentId.toString())
    }

    private fun latestHomework(student: UserEntity): HomeworkEntity? {
        val classIds = myClassIds(student.id)
        if (classIds.isEmpty()) return null
        return homeworkRepository.findAllByIsDraftFalseAndIsActiveTrue()
            .filter { it.classId in classIds && isAssigned(it, student.id) }
            .maxByOrNull { it.dueDate }
    }

    private fun dueLabel(due: Instant, now: Instant): String {
        val days = ChronoUnit.DAYS.between(LocalDate.now(clock), LocalDate.ofInstant(due, clock.zone))
        return when {
            due.isBefore(now) -> "Overdue"
            days <= 0L -> "Today"
            days == 1L -> "Tomorrow"
            else -> "In " + days + " days"
        }
    }

    private fun streakDays(dates: List<Instant>): Int {
        if (dates.isEmpty()) return 0
        val days = dates.map { LocalDate.ofInstant(it, clock.zone) }.toSet()
        val today = LocalDate.now(clock)
        var cursor = when {
            today in days -> today
            today.minusDays(1) in days -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (cursor in days) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }
}
