package com.afrithecus.brainbox.api.parent

import com.afrithecus.brainbox.api.achievements.AchievementsService
import com.afrithecus.brainbox.api.attendance.AttendanceService
import com.afrithecus.brainbox.api.cbcratings.repository.CbcRatingRepository
import com.afrithecus.brainbox.api.cbcratings.repository.CbcStrandRepository
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.contract.LearningContractService
import com.afrithecus.brainbox.api.contract.web.LearningContractPayload
import com.afrithecus.brainbox.api.gradebook.GradebookService
import com.afrithecus.brainbox.api.gradebook.repository.GradebookAssessmentRepository
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.parent.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.parent.web.ParentRecommendationPayload
import com.afrithecus.brainbox.api.parent.web.ChildStatsPayload
import com.afrithecus.brainbox.api.parent.web.EngagementPayload
import com.afrithecus.brainbox.api.parent.web.FamilyComparisonPayload
import com.afrithecus.brainbox.api.parent.web.SiblingComparisonPayload
import com.afrithecus.brainbox.api.parent.web.HomeworkItemPayload
import com.afrithecus.brainbox.api.parent.web.ParentAlertPayload
import com.afrithecus.brainbox.api.parent.web.RecentActivityPayload
import com.afrithecus.brainbox.api.parent.web.SubjectPerformancePayload
import com.afrithecus.brainbox.api.parent.web.UpcomingEventPayload
import com.afrithecus.brainbox.api.parent.web.WeeklyPerformancePayload
import com.afrithecus.brainbox.api.timetable.ParentCalendarService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The data behind the parent dashboard, computed from records the school already keeps:
 * published gradebook entries, teacher attendance registers, homework and its submissions,
 * the child's timetable, CBC strand ratings and the gamification profile.
 *
 * Everything is parent-authorized through [ParentPortalService.requireLinkedChild], so a
 * guardian only ever reads a child they are linked to. Nothing here is invented from a
 * mock: a section with no data is an empty list, not a fabricated row.
 */
@Service
class ParentInsightsService(
    private val portal: ParentPortalService,
    private val attendance: AttendanceService,
    private val grades: GradebookService,
    private val memberships: ClassMembershipRepository,
    private val assessments: GradebookAssessmentRepository,
    private val classes: TeacherClassRepository,
    private val homework: HomeworkRepository,
    private val submissions: HomeworkSubmissionRepository,
    private val calendar: ParentCalendarService,
    private val ratings: CbcRatingRepository,
    private val strands: CbcStrandRepository,
    private val achievements: AchievementsService,
    private val contracts: LearningContractService,
    private val clock: Clock,
) {

    private val zone: ZoneId get() = clock.zone

    /** Average percentage per class subject. */
    @Transactional(readOnly = true)
    fun performance(current: CurrentUser, childId: String): List<SubjectPerformancePayload> {
        val child = portal.requireLinkedChild(current, childId)
        val entries = grades.childGrades(current, child.id.toString(), null)
        if (entries.isEmpty()) return emptyList()
        // A mark carries the assessment's client id; the assessment names the class, and
        // the class names the subject.
        val byClientId = assessments.findAllByClientIdIn(entries.map { it.assessmentId }.toSet())
            .associateBy { it.clientId }
        val subjectOfClass = classes
            .findAllById(byClientId.values.map { it.classId }.distinct())
            .associate { it.id to it.subject }
        return entries
            .filter { it.countsTowardAverage }
            .groupBy { entry ->
                subjectOfClass[byClientId[entry.assessmentId]?.classId] ?: entry.assessmentType
            }
            .map { (subject, rows) -> SubjectPerformancePayload(subject = subject, score = rows.map { it.percentage }.average()) }
            .sortedByDescending { it.score }
    }

    /** Headline numbers for the child: average, rank, streak, XP and badges. */
    @Transactional(readOnly = true)
    fun stats(current: CurrentUser, childId: String): ChildStatsPayload {
        val child = portal.requireLinkedChild(current, childId)
        val entries = grades.childGrades(current, child.id.toString(), null).filter { it.countsTowardAverage }
        val profile = achievements.forUser(child.id)
        return ChildStatsPayload(
            avgScore = entries.map { it.percentage }.average().takeIf { !it.isNaN() }?.toInt() ?: 0,
            nationalRank = profile.rankPosition,
            streakDays = profile.currentStreak,
            totalXP = profile.totalXP,
            badges = profile.badges.map { it.id },
        )
    }

    /**
     * Things a guardian should act on: repeated absence, lateness and marks below the
     * pass line. Ordered newest first and capped so the banner stays readable.
     */
    @Transactional(readOnly = true)
    fun alerts(current: CurrentUser, childId: String): List<ParentAlertPayload> {
        val child = portal.requireLinkedChild(current, childId)
        val alerts = mutableListOf<ParentAlertPayload>()

        attendance.parentAttendance(current, child.id.toString())
            .filter { it.status == "ABSENT" || it.status == "LATE" }
            .take(MAX_ALERTS)
            .forEach { record ->
                val absent = record.status == "ABSENT"
                alerts += ParentAlertPayload(
                    id = "att_" + child.id + "_" + record.date,
                    title = if (absent) "Absent from class" else "Arrived late",
                    message = (record.reason?.takeIf { it.isNotBlank() }
                        ?: if (absent) "Your child was marked absent." else "Your child arrived late."),
                    priority = if (absent) "CRITICAL" else "ACADEMIC",
                    category = if (absent) "DANGER" else "WARNING",
                    timestamp = record.date,
                )
            }

        grades.childGrades(current, child.id.toString(), null)
            .filter { it.countsTowardAverage && it.percentage < PASS_PERCENTAGE }
            .take(MAX_ALERTS)
            .forEach { entry ->
                alerts += ParentAlertPayload(
                    id = "grade_" + entry.id,
                    title = "Needs support in " + entry.assessmentTitle,
                    message = entry.assessmentTitle + ": " + entry.score + "/" + entry.maxScore +
                        " (" + entry.percentage + "%).",
                    priority = "ACADEMIC",
                    category = if (entry.percentage < FAILURE_PERCENTAGE) "DANGER" else "WARNING",
                    timestamp = entry.gradedAt,
                )
            }

        return alerts.sortedByDescending { it.timestamp }.take(MAX_ALERTS)
    }

    /** Recently published marks and homework the child handed in. */
    @Transactional(readOnly = true)
    fun activities(current: CurrentUser, childId: String): List<RecentActivityPayload> {
        val child = portal.requireLinkedChild(current, childId)
        val activities = mutableListOf<RecentActivityPayload>()

        grades.childGrades(current, child.id.toString(), null).forEach { entry ->
            activities += RecentActivityPayload(
                id = "grade_" + entry.id,
                title = entry.assessmentTitle,
                subtitle = entry.score.toString() + "/" + entry.maxScore + " - " + entry.gradeBand,
                type = "GRADE_PUBLISHED",
                timestamp = entry.gradedAt,
            )
        }

        val homeworkById = homeworkFor(child.id).associateBy { it.id }
        submissions.findAllByStudentId(child.id).forEach { submission ->
            val item = homeworkById[submission.homeworkId] ?: return@forEach
            activities += RecentActivityPayload(
                id = "hw_" + submission.id,
                title = item.title,
                subtitle = if (submission.status == SubmissionStatus.GRADED) {
                    "Graded " + (submission.grade?.toString() ?: "-")
                } else {
                    "Submitted"
                },
                type = "HOMEWORK_SUBMITTED",
                timestamp = submission.submittedAt.toEpochMilli(),
                status = submission.status.name,
            )
        }

        return activities.sortedByDescending { it.timestamp }.take(MAX_ACTIVITIES)
    }

    /** Seven day trend of graded percentages, oldest first, 0 when nothing was marked. */
    @Transactional(readOnly = true)
    fun weeklyPerformance(current: CurrentUser, childId: String): WeeklyPerformancePayload {
        val child = portal.requireLinkedChild(current, childId)
        val byDay = grades.childGrades(current, child.id.toString(), null)
            .filter { it.countsTowardAverage }
            .groupBy { Instant.ofEpochMilli(it.gradedAt).atZone(zone).toLocalDate() }
        val today = LocalDate.now(clock)
        return WeeklyPerformancePayload(
            scores = (6 downTo 0).map { offset ->
                byDay[today.minusDays(offset.toLong())]
                    ?.map { it.percentage }
                    ?.average()
                    ?.toInt()
                    ?: 0
            },
        )
    }

    /** Timetable and school events from today onward. */
    @Transactional(readOnly = true)
    fun upcomingEvents(current: CurrentUser, childId: String): List<UpcomingEventPayload> {
        portal.requireLinkedChild(current, childId)
        val startOfToday = LocalDate.now(clock).atStartOfDay(zone).toInstant().toEpochMilli()
        return calendar.calendar(current, childId)
            .filter { it.date >= startOfToday }
            .sortedBy { it.date }
            .take(MAX_EVENTS)
            .map { UpcomingEventPayload(title = it.title, date = it.date, type = it.type) }
    }

    /** Homework set for the child's classes, newest due date first, with submission state. */
    @Transactional(readOnly = true)
    fun homework(current: CurrentUser, childId: String): List<HomeworkItemPayload> {
        val child = portal.requireLinkedChild(current, childId)
        val byId = submissions.findAllByStudentId(child.id).associateBy { it.homeworkId }
        val now = clock.instant()
        return homeworkFor(child.id)
            .sortedByDescending { it.dueDate }
            .take(MAX_HOMEWORK)
            .map { item ->
                val submission = byId[item.id]
                HomeworkItemPayload(
                    id = item.id,
                    subject = item.subject,
                    title = item.title,
                    dueDate = item.dueDate.toEpochMilli(),
                    status = when {
                        submission?.status == SubmissionStatus.GRADED -> "GRADED"
                        submission != null -> "SUBMITTED"
                        item.dueDate.isBefore(now) -> "OVERDUE"
                        else -> "NOT_STARTED"
                    },
                    grade = submission?.grade,
                    maxGrade = null,
                    submissionUrl = submission?.attachmentUrl,
                )
            }
    }

    /**
     * Engagement is a summary of the same records: how much was marked, handed in and
     * attended this week versus this month. The factors explain the number instead of
     * leaving a bare percentage.
     */
    @Transactional(readOnly = true)
    fun engagement(current: CurrentUser, childId: String): EngagementPayload {
        val child = portal.requireLinkedChild(current, childId)
        val today = LocalDate.now(clock)
        val weekStart = today.minusDays(6)
        val monthStart = today.minusDays(29)

        val records = attendance.parentAttendance(current, child.id.toString())
        val week = records.filter { attendanceDateOf(it.date) >= weekStart }
        val month = records.filter { attendanceDateOf(it.date) >= monthStart }

        val graded = grades.childGrades(current, child.id.toString(), null)
        val weekGraded = graded.count { attendanceDateOf(it.gradedAt) >= weekStart }
        val monthGraded = graded.count { attendanceDateOf(it.gradedAt) >= monthStart }
        val submissionsByDay = submissions.findAllByStudentId(child.id)
            .map { attendanceDateOf(it.submittedAt.toEpochMilli()) }
        val weekSubmitted = submissionsByDay.count { it >= weekStart }
        val monthSubmitted = submissionsByDay.count { it >= monthStart }

        val weekPresent = week.count { it.isPresent }
        val monthPresent = month.count { it.isPresent }
        return EngagementPayload(
            weekly = weeklyEngagementScore(week.size, weekPresent, weekGraded, weekSubmitted),
            monthly = weeklyEngagementScore(month.size, monthPresent, monthGraded, monthSubmitted),
            factors = listOf(
                weekPresent.toString() + " of " + week.size + " sessions attended this week",
                weekGraded.toString() + " marks published this week",
                weekSubmitted.toString() + " homework handed in this week",
            ),
        )
    }

    /**
     * The guardian's linked children side by side, so a parent with more than one child
     * at the school can see how each is doing without opening two dashboards.
     *
     * The numbers are the same ones the child-scoped reads produce (counted gradebook
     * percentages, the attendance register, this week's engagement formula and the
     * gamification profile), and each is null when the school holds no data for it.
     */
    @Transactional(readOnly = true)
    fun familyComparison(current: CurrentUser): FamilyComparisonPayload {
        val children = portal.linkedChildren(current)
        val today = LocalDate.now(clock)
        val weekStart = today.minusDays(6)
        val rows = children.map { child ->
            val graded = grades.childGrades(current, child.id.toString(), null)
            val counted = graded.filter { it.countsTowardAverage }
            val records = attendance.parentAttendance(current, child.id.toString())
            val week = records.filter { attendanceDateOf(it.date) >= weekStart }
            val gradedThisWeek = graded.count { attendanceDateOf(it.gradedAt) >= weekStart }
            val submittedThisWeek = submissions.findAllByStudentId(child.id)
                .count { attendanceDateOf(it.submittedAt.toEpochMilli()) >= weekStart }
            val profile = achievements.forUser(child.id)
            SiblingComparisonPayload(
                childId = child.id.toString(),
                name = child.name,
                grade = child.gradeLevel.orEmpty(),
                avatarUrl = null,
                averageScore = counted
                    .takeIf { it.isNotEmpty() }
                    ?.let { rows -> (rows.map { row -> row.percentage }.average()).toInt().coerceIn(0, 100) },
                attendanceRate = records
                    .takeIf { it.isNotEmpty() }
                    ?.let { rows -> (rows.count { it.isPresent } * 100) / rows.size },
                engagementScore = weeklyEngagementScore(
                    attendanceRows = week.size,
                    present = week.count { it.isPresent },
                    marked = gradedThisWeek,
                    handedIn = submittedThisWeek,
                ),
                streakDays = profile.currentStreak,
                badges = profile.badges.size,
            )
        }
        return FamilyComparisonPayload(children = rows, generatedAt = clock.millis())
    }

    /** The teacher's CBC strand ratings for the child, newest first. */
    @Transactional(readOnly = true)
    fun cbcRatings(current: CurrentUser, childId: String): List<CbcStrandRatingPayload> {
        val child = portal.requireLinkedChild(current, childId)
        return ratings.findAllByStudentIdOrderByRatedAtDesc(child.id)
            .distinctBy { it.strandCode }
            .map { row ->
                CbcStrandRatingPayload(
                    strandCode = row.strandCode,
                    descriptor = strands.findByCode(row.strandCode)?.name ?: row.strandCode,
                    rating = row.rating,
                    evidenceLink = row.evidence,
                )
            }
    }

    /** The child's gamification profile (level, XP, streak, badges, rank). */
    @Transactional(readOnly = true)
    fun achievements(current: CurrentUser, childId: String): UserAchievementsPayload {
        val child = portal.requireLinkedChild(current, childId)
        return achievements.forUser(child.id)
    }

    /**
     * The child's active learning contract, when a teacher has one in flight.
     * A guardian with no contract gets 404: there is nothing to show.
     */
    @Transactional(readOnly = true)
    fun contract(current: CurrentUser, childId: String): LearningContractPayload {
        val child = portal.requireLinkedChild(current, childId)
        return contracts.latestForChild(child.id) ?: throw notFound("No learning contract for this child")
    }

    /**
     * Parent-facing guidance derived from the same records the dashboard shows: what to act
     * on, why, and where to go. This is not the learner's content feed.
     */
    @Transactional(readOnly = true)
    fun recommendations(current: CurrentUser, childId: String): List<ParentRecommendationPayload> {
        val child = portal.requireLinkedChild(current, childId)
        val out = mutableListOf<ParentRecommendationPayload>()

        val attendanceRate = attendance.parentPerformance(current, child.id.toString()).attendancePercentage
        if (attendanceRate < ATTENDANCE_CONCERN) {
            out += ParentRecommendationPayload(
                id = "rec_attendance",
                action = "Review your child's attendance",
                reason = "Attendance is at " + attendanceRate.toInt() + "% this term.",
                ctaRoute = CP_ROUTE_CONFERENCE,
                priority = if (attendanceRate < ATTENDANCE_CRITICAL) "HIGH" else "MEDIUM",
            )
        }

        performance(current, childId).filter { it.score < PASS_PERCENTAGE }.take(2).forEach { row ->
            out += ParentRecommendationPayload(
                id = "rec_subject_" + row.subject,
                action = "Ask about " + row.subject,
                reason = row.subject + " is averaging " + row.score.toInt() + "%.",
                ctaRoute = CP_ROUTE_MESSAGES,
                priority = if (row.score < FAILURE_PERCENTAGE) "HIGH" else "MEDIUM",
            )
        }

        val overdue = homework(current, childId).count { it.status == "OVERDUE" }
        if (overdue > 0) {
            out += ParentRecommendationPayload(
                id = "rec_homework",
                action = "Follow up on " + overdue + " overdue homework" + (if (overdue == 1) "" else "s"),
                reason = "Homework past its due date has not been handed in.",
                ctaRoute = CP_ROUTE_MESSAGES,
                priority = if (overdue > 2) "HIGH" else "MEDIUM",
            )
        }

        val engagement = engagement(current, childId)
        if (engagement.weekly in 1 until ENGAGEMENT_CONCERN) {
            out += ParentRecommendationPayload(
                id = "rec_engagement",
                action = "Re-establish the study routine",
                reason = "Engagement this week is " + engagement.weekly + "%.",
                ctaRoute = CP_ROUTE_ACHIEVEMENTS,
                priority = "MEDIUM",
            )
        }

        if (out.isEmpty()) {
            out += ParentRecommendationPayload(
                id = "rec_steady",
                action = "Nothing needs attention",
                reason = "Attendance, marks and homework are all on track.",
                ctaRoute = CP_ROUTE_ACHIEVEMENTS,
                priority = "LOW",
            )
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    /** The child's classes' homework, honouring per-learner assignment when set. */
    /**
     * Attendance and marked work as one percentage. Shared by the dashboard gauge and the
     * family comparison so a sibling's score cannot disagree with their own dashboard.
     */
    private fun weeklyEngagementScore(
        attendanceRows: Int,
        present: Int,
        marked: Int,
        handedIn: Int,
    ): Int {
        if (attendanceRows == 0 && marked == 0 && handedIn == 0) return 0
        val attendancePart = if (attendanceRows == 0) 1.0 else present.toDouble() / attendanceRows
        val workPart = ((marked + handedIn).coerceAtMost(MAX_WORK_FOR_FULL_SCORE)).toDouble() /
            MAX_WORK_FOR_FULL_SCORE
        return ((attendancePart * ATTENDANCE_WEIGHT + workPart * WORK_WEIGHT) * 100).toInt()
    }

    /** Epoch millis in the school's zone, so week boundaries mean the school's week. */
    private fun attendanceDateOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun homeworkFor(childId: UUID): List<HomeworkEntity> {
        val classIds = memberships.findAllByStudentId(childId).map { it.classId }
        if (classIds.isEmpty()) return emptyList()
        return classIds
            .flatMap { homework.findAllByClassIdOrderByDueDateDesc(it) }
            .filter { it.isActive && !it.isDraft }
            .filter { item ->
                val assigned = item.assignedStudentIds?.takeIf { it.isNotBlank() } ?: return@filter true
                assigned.split(",").map { it.trim() }.any { it == childId.toString() }
            }
            .distinctBy { it.id }
    }

    private companion object {
        const val PASS_PERCENTAGE = 50
        const val FAILURE_PERCENTAGE = 35
        const val MAX_ALERTS = 10
        const val MAX_ACTIVITIES = 20
        const val MAX_EVENTS = 10
        const val MAX_HOMEWORK = 20
        const val MAX_WORK_FOR_FULL_SCORE = 8
        const val ATTENDANCE_WEIGHT = 0.5
        const val WORK_WEIGHT = 0.5
        const val ATTENDANCE_CONCERN = 80.0
        const val ATTENDANCE_CRITICAL = 60.0
        const val ENGAGEMENT_CONCERN = 40

        /** Routes the Android app already has for these destinations. */
        const val CP_ROUTE_CONFERENCE = "conference_scheduler"
        const val CP_ROUTE_MESSAGES = "message_centre"
        const val CP_ROUTE_ACHIEVEMENTS = "achievements"
    }
}
