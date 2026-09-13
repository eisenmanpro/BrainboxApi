package com.afrithecus.brainbox.api.teacher

import com.afrithecus.brainbox.api.announcement.repository.TeacherAnnouncementRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMessageRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupReadRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupRepository
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.cbcratings.CbcAnalyticsService
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.conference.repository.ConferenceBookingRepository
import com.afrithecus.brainbox.api.conference.repository.ConferenceSlotRepository
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import com.afrithecus.brainbox.api.homework.model.GradingMode
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.teacher.web.ClassPerformanceView
import com.afrithecus.brainbox.api.teacher.web.TeacherAnnouncementPreviewView
import com.afrithecus.brainbox.api.teacher.web.TeacherClassView
import com.afrithecus.brainbox.api.teacher.web.TeacherDashboardPayload
import com.afrithecus.brainbox.api.teacher.web.TeacherHomeworkView
import com.afrithecus.brainbox.api.teacher.web.TeacherSubmissionView
import com.afrithecus.brainbox.api.teacher.web.TeacherUpcomingItemView
import com.afrithecus.brainbox.api.timetable.entity.TimetableEntryEntity
import com.afrithecus.brainbox.api.timetable.repository.TimetableEntryRepository
import com.afrithecus.brainbox.api.traditional.model.TraditionalEditStatus
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import com.afrithecus.brainbox.api.traditional.repository.TraditionalEditRequestRepository
import com.afrithecus.brainbox.api.traditional.repository.TraditionalExamRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Teacher dashboard composition (docs/ongoing/api_teacher_roster_changes.md).
 * Every count is derived from the teacher's own classes/work queues so the badge
 * values are real rather than client-fabricated; lists the client does not source
 * from the backend are omitted and default to empty on the wire.
 */
@Service
class TeacherDashboardService(
    private val userRepository: UserRepository,
    private val classRepository: TeacherClassRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val homeworkRepository: HomeworkRepository,
    private val submissionRepository: HomeworkSubmissionRepository,
    private val timetableRepository: TimetableEntryRepository,
    private val classGroupRepository: ClassGroupRepository,
    private val classGroupMessageRepository: ClassGroupMessageRepository,
    private val classGroupReadRepository: ClassGroupReadRepository,
    private val conferenceSlotRepository: ConferenceSlotRepository,
    private val conferenceBookingRepository: ConferenceBookingRepository,
    private val traditionalExamRepository: TraditionalExamRepository,
    private val traditionalEditRequestRepository: TraditionalEditRequestRepository,
    private val announcementRepository: TeacherAnnouncementRepository,
    private val cbcAnalytics: CbcAnalyticsService,
    private val codec: QuestionCodec,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
) {

    @Transactional(readOnly = true)
    fun dashboard(current: CurrentUser): TeacherDashboardPayload {
        val teacher = userRepository.findById(current.userId).orElse(null) ?: throw notFound("User not found")
        if (teacher.role != Role.TEACHER && teacher.role != Role.ADMIN) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher dashboard is staff-only")
        }
        val zone = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneId.of("Africa/Nairobi"))
        val now = clock.instant()
        val classes = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
        val classViews = classes.map { toClassView(it, teacher) }
        val classById = classes.associateBy { it.id }

        val homeworks = homeworkRepository.findAllByTeacherIdAndIsActiveTrueOrderByDueDateAsc(teacher.id)
            .filter { !it.isDraft }
        val submissions = homeworks.flatMap { submissionRepository.findAllByHomeworkId(it.id) }
        val ungraded = submissions.filter { it.status == SubmissionStatus.PENDING }
        val studentNames = userRepository.findAllById(ungraded.map { it.studentId }.distinct())
            .associate { it.id to it.name }

        // Bookings are auto-confirmed server-side, so the teacher's actionable
        // "conference requests" are confirmed bookings on still-open future slots.
        val conferenceCount = conferenceSlotRepository.findAllByTeacherIdOrderBySlotDateAsc(teacher.id)
            .filter { !it.slotDate.isBefore(now) && it.status != "CANCELLED" && it.status != "COMPLETED" }
            .sumOf { conferenceBookingRepository.countBySlotIdAndStatus(it.id, "CONFIRMED").toInt() }

        val pendingStudents = userRepository.findByJoinedTeacherId(teacher.id)
            .count { it.role == Role.STUDENT && it.verificationStatus == AccountStatus.PENDING_VERIFICATION }
        val pendingTeachers = if (teacher.subRole == SubRole.ICT_ADMIN && teacher.schoolId != null) {
            userRepository.findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(teacher.schoolId!!)
                .count { it.role == Role.TEACHER && it.verificationStatus == AccountStatus.PENDING_VERIFICATION }
        } else {
            0
        }

        val coordinator = teacher.subRole == SubRole.GRADE_COORDINATOR || teacher.subRole == SubRole.ICT_ADMIN
        val schoolExams = if (coordinator && teacher.schoolId != null) {
            traditionalExamRepository.findAllBySchoolIdOrderByCreatedAtDesc(teacher.schoolId!!)
        } else {
            emptyList()
        }
        val pendingFinalizations = schoolExams.count {
            it.status == TraditionalExamStatus.CONFIRMED || it.status == TraditionalExamStatus.PRE_FINAL
        }
        val pendingEditRequests = schoolExams.sumOf {
            traditionalEditRequestRepository.findAllByExamIdAndStatus(it.id, TraditionalEditStatus.PENDING).size
        }

        val dayOfWeek = LocalDate.now(zone).dayOfWeek.value
        val todayEntries = timetableRepository.findAllByTeacherIdAndDayOfWeekOrderByStartTimeAsc(teacher.id, dayOfWeek)
        val todayClasses = todayEntries.mapNotNull { entry ->
            classById[parseUuid(entry.classId)]?.let { toClassView(it, teacher) } ?: minimalClassView(entry, teacher)
        }.distinctBy { it.id }

        val announcements = teacher.schoolId?.let { schoolId ->
            announcementRepository.findAllByTeacherIdNotAndSchoolIdOrderBySentAtDesc(teacher.id, schoolId)
                .take(3)
                .map { TeacherAnnouncementPreviewView(it.title, it.content.take(160), format(it.sentAt, zone)) }
        }.orEmpty()

        val unread = classGroupRepository.findAllByTeacherIdOrderByUpdatedAtDesc(teacher.id).sumOf { group ->
            val lastRead = classGroupReadRepository.findByGroupIdAndUserId(group.id, teacher.id)?.lastReadAt
                ?: group.createdAt
            classGroupMessageRepository.countByGroupIdAndCreatedAtAfterAndSenderIdNot(group.id, lastRead, teacher.id)
        }.toInt()

        return TeacherDashboardPayload(
            classes = classViews,
            pendingGradingCount = ungraded.size,
            todayClasses = todayClasses,
            upcomingDeadlines = homeworks.filter { it.dueDate.isAfter(now) }.take(10).map { toHomeworkView(it) },
            urgentQueue = ungraded.sortedByDescending { it.submittedAt }.take(10).map { toSubmissionView(it, studentNames) },
            classPerformance = classes.mapNotNull { clazz ->
                runCatching { cbcAnalytics.classReport(teacher, clazz.id.toString(), "") }.getOrNull()?.let { report ->
                    ClassPerformanceView(
                        classId = report.classId,
                        className = report.className,
                        subject = clazz.subject,
                        averageScore = report.overallClassAverage,
                        strandBreakdown = report.strandMastery.associate { it.strandName to it.classAverage },
                    )
                }
            },
            unreadMessagesCount = unread,
            pendingApprovalsCount = pendingStudents + pendingTeachers,
            pendingFinalizationsCount = pendingFinalizations,
            pendingEditRequestsCount = pendingEditRequests,
            conferenceRequestsCount = conferenceCount,
            announcements = announcements,
            upcomingItems = upcomingItems(todayEntries, ungraded.size, conferenceCount, zone),
        )
    }

    // ------------------------------------------------------------- internals

    private fun upcomingItems(
        todayEntries: List<TimetableEntryEntity>,
        pendingGrading: Int,
        conferenceCount: Int,
        zone: ZoneId,
    ): List<TeacherUpcomingItemView> {
        val items = mutableListOf<TeacherUpcomingItemView>()
        val currentTime = java.time.LocalTime.now(clock.withZone(zone)).toString().take(5)
        todayEntries.firstOrNull { it.startTime >= currentTime }?.let {
            items += TeacherUpcomingItemView(
                title = "Next Class",
                detail = listOf(it.className, it.subject, it.startTime).filter { value -> value.isNotBlank() }.joinToString(" - "),
                type = "CALENDAR",
            )
        }
        if (pendingGrading > 0) {
            items += TeacherUpcomingItemView("Pending Grades", pendingGrading.toString() + " submissions awaiting review", "ASSIGNMENT")
        }
        if (conferenceCount > 0) {
            items += TeacherUpcomingItemView("Conference Requests", conferenceCount.toString() + " awaiting confirmation", "VIDEO")
        }
        return items
    }

    private fun toClassView(clazz: com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity, teacher: UserEntity): TeacherClassView =
        TeacherClassView(
            id = clazz.id.toString(),
            name = clazz.name,
            grade = gradeNumber(clazz.gradeLevel),
            subject = clazz.subject,
            teacherId = teacher.id.toString(),
            teacherName = teacher.name,
            schoolId = clazz.schoolId?.toString() ?: "",
            studentCount = membershipRepository.countByClassId(clazz.id).toInt(),
            createdAt = clazz.createdAt.toEpochMilli(),
            isActive = clazz.isActive,
        )

    private fun minimalClassView(entry: TimetableEntryEntity, teacher: UserEntity): TeacherClassView = TeacherClassView(
        id = entry.classId ?: entry.className,
        name = entry.className,
        grade = 0,
        subject = entry.subject,
        teacherId = teacher.id.toString(),
        teacherName = teacher.name,
        schoolId = entry.schoolId?.toString() ?: "",
        studentCount = 0,
        createdAt = entry.createdAt.toEpochMilli(),
    )

    private fun toHomeworkView(entity: HomeworkEntity) = TeacherHomeworkView(
        id = entity.id,
        classId = entity.classId.toString(),
        teacherId = entity.teacherId.toString(),
        schoolId = entity.schoolId?.toString() ?: "",
        teacherName = entity.teacherName,
        title = entity.title,
        description = entity.description,
        subject = entity.subject,
        gradeLevel = entity.gradeLevel,
        dueDate = entity.dueDate.toEpochMilli(),
        submissionType = entity.submissionType.name,
        assignedStudentIds = codec.parseList(entity.assignedStudentIds).orEmpty(),
        createdAt = entity.createdAt.toEpochMilli(),
        isActive = entity.isActive,
        gradingMode = (entity.gradingMode ?: GradingMode.AUTO_IMMEDIATE).name,
        scope = entity.scope.name,
        isDraft = entity.isDraft,
        checklistItems = codec.parseList(entity.checklistItems).orEmpty(),
    )

    private fun toSubmissionView(entity: HomeworkSubmissionEntity, names: Map<UUID, String>) = TeacherSubmissionView(
        id = entity.id.toString(),
        homeworkId = entity.homeworkId,
        studentId = entity.studentId.toString(),
        studentName = names[entity.studentId] ?: "Student",
        submissionText = entity.submissionText,
        attachmentUrl = entity.attachmentUrl,
        submittedAt = entity.submittedAt.toEpochMilli(),
        grade = entity.grade,
        feedback = entity.feedback,
        isGraded = entity.status == SubmissionStatus.GRADED || entity.gradedAt != null,
        cbcStrandTag = entity.cbcStrandTag,
        status = if (entity.status == SubmissionStatus.PENDING) "SUBMITTED" else entity.status.name,
    )

    private fun format(instant: java.time.Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(zone).format(instant)

    private fun gradeNumber(gradeLevel: String): Int =
        Regex("\\d+").find(gradeLevel)?.value?.toIntOrNull() ?: 0

    private fun parseUuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
