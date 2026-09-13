package com.afrithecus.brainbox.api.studentanalytics

import com.afrithecus.brainbox.api.achievements.AchievementsService
import com.afrithecus.brainbox.api.achievements.web.UserAchievementsPayload
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.cbcratings.repository.CbcRatingRepository
import com.afrithecus.brainbox.api.cbcratings.repository.CbcStrandRepository
import com.afrithecus.brainbox.api.cbcratings.web.CbcStrandRatingPayload
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.contract.LearningContractService
import com.afrithecus.brainbox.api.contract.web.LearningContractPayload
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.feedback.repository.TeacherFeedbackRepository
import com.afrithecus.brainbox.api.feedback.web.TeacherFeedbackPayload
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.repository.LearningViewRepository
import com.afrithecus.brainbox.api.studentanalytics.web.ClassComparisonsPayload
import com.afrithecus.brainbox.api.studentanalytics.web.EngagementScorePayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentAnalyticsPayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentAttendancePayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentHomeworkPayload
import com.afrithecus.brainbox.api.studentanalytics.web.StudentQuickStatsPayload
import com.afrithecus.brainbox.api.studentanalytics.web.SubjectPerformancePayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Teacher student-analytics surface (doc 04 section 14): a per-learner
 * aggregate plus its component endpoints, composed from the exam, attendance,
 * homework, learning, feedback, contract and CBC data the teacher can already see.
 * Access is limited to teachers who share a class with the learner (coordinators
 * may read any).
 */
@Service
class StudentAnalyticsService(
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val classRepository: TeacherClassRepository,
    private val attendanceRepository: AttendanceRecordRepository,
    private val examSubmissionRepository: ExamSubmissionRepository,
    private val examRepository: ExamRepository,
    private val homeworkSubmissionRepository: HomeworkSubmissionRepository,
    private val learningViewRepository: LearningViewRepository,
    private val feedbackRepository: TeacherFeedbackRepository,
    private val contractService: LearningContractService,
    private val cbcRatingRepository: CbcRatingRepository,
    private val cbcStrandRepository: CbcStrandRepository,
    private val achievementsService: AchievementsService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun studentAnalytics(teacher: UserEntity, studentIdRaw: String): StudentAnalyticsPayload {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        val performance = subjectPerformance(teacher, studentIdRaw)
        val attendance = attendance(teacher, studentIdRaw, 0, 0)
        val achievements = achievementsService.forUser(student.id)
        val average = if (performance.isEmpty()) 0 else performance.map { it.score }.average().toInt()
        val attendancePct = if (attendance.isEmpty()) {
            0.0
        } else {
            attendance.count { it.status == "PRESENT" || it.status == "LATE" }.toDouble() / attendance.size * 100.0
        }
        val classes = classesFor(teacher, student)
        val comparisons = classes.firstOrNull()?.let { classComparisons(teacher, it.id.toString()) }
        return StudentAnalyticsPayload(
            studentId = student.id.toString(),
            studentName = student.name,
            quickStats = StudentQuickStatsPayload(
                avgScore = average,
                streakDays = achievements.currentStreak,
                attendancePercentage = round2(attendancePct),
                totalXP = achievements.totalXP,
                nationalRank = achievements.rankPosition,
            ),
            weeklyEngagement = weeklyEngagement(student.id),
            subjectPerformance = performance,
            cbcCompetencies = cbcCompetencies(teacher, studentIdRaw),
            attendance = attendance,
            engagement = engagement(teacher, studentIdRaw),
            homeworkHistory = homeworkHistory(teacher, studentIdRaw),
            feedbackHistory = feedbackHistory(teacher, studentIdRaw),
            learningContract = learningContract(teacher, studentIdRaw),
            classComparisons = comparisons,
        )
    }

    @Transactional(readOnly = true)
    fun subjectPerformance(teacher: UserEntity, studentIdRaw: String): List<SubjectPerformancePayload> {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        return examSubmissionRepository.findAllByUserId(student.id)
            .mapNotNull { submission ->
                examRepository.findById(submission.examId).orElse(null)?.let { it.subject to submission.percentage.toDouble() }
            }
            .groupBy({ it.first }, { it.second })
            .map { (subject, scores) -> SubjectPerformancePayload(subject, round2(scores.average())) }
            .sortedByDescending { it.score }
    }

    @Transactional(readOnly = true)
    fun cbcCompetencies(teacher: UserEntity, studentIdRaw: String): List<CbcStrandRatingPayload> {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        val ratings = cbcRatingRepository.findAllByStudentIdOrderByRatedAtDesc(student.id)
            .distinctBy { it.strandCode }
        val strands = cbcStrandRepository.findAllByCodeIn(ratings.map { it.strandCode }.toSet()).associateBy { it.code }
        return ratings.map {
            CbcStrandRatingPayload(
                strandCode = it.strandCode,
                descriptor = strands[it.strandCode]?.descriptor ?: "",
                rating = it.rating,
                evidenceLink = it.evidence,
            )
        }
    }

    @Transactional(readOnly = true)
    fun attendance(teacher: UserEntity, studentIdRaw: String, startDate: Long, endDate: Long): List<StudentAttendancePayload> {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        return attendanceRepository.findAllByStudentIdOrderByAttendanceDateDesc(student.id)
            .filter { record ->
                val millis = record.attendanceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                (startDate <= 0 || millis >= startDate) && (endDate <= 0 || millis <= endDate)
            }
            .map { record ->
                StudentAttendancePayload(
                    id = record.id.toString(),
                    classId = record.classId.toString(),
                    studentId = record.studentId.toString(),
                    studentName = student.name,
                    date = record.attendanceDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    status = record.status.name,
                    notes = record.notes,
                    recordedBy = record.recordedByName ?: record.recordedBy?.toString() ?: "",
                    isAutoFromLiveClass = record.isAutoFromLiveClass,
                )
            }
    }

    @Transactional(readOnly = true)
    fun engagement(teacher: UserEntity, studentIdRaw: String): EngagementScorePayload {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        val views = learningViewRepository.findAllByUserId(student.id)
        val today = LocalDate.now(clock)
        val weekly = views.count { it.dayKey >= today.minusDays(6).toString() }
        val monthly = views.count { it.dayKey >= today.minusDays(29).toString() }
        val factors = buildList {
            if (weekly >= 5) add("Consistent weekly activity")
            if (monthly >= 15) add("Strong monthly engagement")
            if (weekly == 0) add("No activity in the last 7 days")
            if (views.isEmpty()) add("No learning-hub views recorded")
        }
        return EngagementScorePayload(weekly = weekly, monthly = monthly, factors = factors)
    }

    @Transactional(readOnly = true)
    fun homeworkHistory(teacher: UserEntity, studentIdRaw: String): List<StudentHomeworkPayload> {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        return homeworkSubmissionRepository.findAllByStudentId(student.id)
            .sortedByDescending { it.submittedAt }
            .map { submission ->
                StudentHomeworkPayload(
                    id = submission.id.toString(),
                    homeworkId = submission.homeworkId,
                    studentId = student.id.toString(),
                    studentName = student.name,
                    submissionText = submission.submissionText,
                    attachmentUrl = submission.attachmentUrl,
                    submittedAt = submission.submittedAt.toEpochMilli(),
                    grade = submission.grade,
                    feedback = submission.feedback,
                    isGraded = submission.grade != null,
                    cbcStrandTag = submission.cbcStrandTag,
                    status = submission.status.name,
                )
            }
    }

    @Transactional(readOnly = true)
    fun feedbackHistory(teacher: UserEntity, studentIdRaw: String): List<TeacherFeedbackPayload> {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        return feedbackRepository.findAllByStudentIdOrderByCreatedAtDesc(student.id).map {
            TeacherFeedbackPayload(
                id = it.clientId,
                submissionId = it.submissionId.orEmpty(),
                teacherId = it.teacherId.toString(),
                studentId = it.studentId.toString(),
                textFeedback = it.textFeedback,
                voiceFeedbackUrl = it.voiceFeedbackUrl,
                createdAt = it.createdAt.toEpochMilli(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun learningContract(teacher: UserEntity, studentIdRaw: String): LearningContractPayload? {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        return contractService.latestForChild(student.id)
    }

    @Transactional(readOnly = true)
    fun classComparisons(teacher: UserEntity, classIdRaw: String): ClassComparisonsPayload {
        requireTeacher(teacher)
        val clazz = requireClassAccess(teacher, classIdRaw)
        val studentIds = membershipRepository.findAllByClassId(clazz.id).map { it.studentId }
        val submissions = studentIds.flatMap { examSubmissionRepository.findAllByUserId(it) }
        val classAverage = if (submissions.isEmpty()) 0.0 else submissions.map { it.percentage.toDouble() }.average()
        val bySubject = submissions
            .mapNotNull { submission ->
                examRepository.findById(submission.examId).orElse(null)?.let { it.subject to submission.percentage.toDouble() }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { round2(it.value.average()) }
        return ClassComparisonsPayload(
            classAverageScore = round2(classAverage),
            studentPercentile = 0,
            subjectComparisons = bySubject,
        )
    }

    @Transactional(readOnly = true)
    fun achievements(teacher: UserEntity, studentIdRaw: String): UserAchievementsPayload {
        requireTeacher(teacher)
        val student = requireStudent(teacher, studentIdRaw)
        return achievementsService.forUser(student.id)
    }

    // ------------------------------------------------------------ internals

    private fun weeklyEngagement(studentId: UUID): List<Int> {
        val views = learningViewRepository.findAllByUserId(studentId)
        val today = LocalDate.now(clock)
        return (6 downTo 0).map { offset ->
            views.count { it.dayKey == today.minusDays(offset.toLong()).toString() }
        }
    }

    private fun classesFor(teacher: UserEntity, student: UserEntity) =
        classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
            .filter { clazz -> membershipRepository.findByClassIdAndStudentId(clazz.id, student.id) != null }

    private fun requireStudent(teacher: UserEntity, raw: String): UserEntity {
        val student = userRepository.findById(parseUuid(raw, "studentId")).orElse(null)
            ?: throw notFound("Learner not found")
        requireStudentAccess(teacher, student)
        return student
    }

    private fun requireStudentAccess(teacher: UserEntity, student: UserEntity) {
        if (teacher.subRole == SubRole.GRADE_COORDINATOR || teacher.subRole == SubRole.ICT_ADMIN) return
        val studentClasses = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
        val teacherClasses = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
            .map { it.id }
            .toSet()
        if (studentClasses.intersect(teacherClasses).isEmpty()) throw forbidden("Not your learner")
    }

    private fun requireClassAccess(teacher: UserEntity, classIdRaw: String) =
        classRepository.findById(parseUuid(classIdRaw, "classId")).orElse(null)
            ?.also { clazz ->
                if (clazz.teacherUserId != teacher.id &&
                    teacher.subRole != SubRole.GRADE_COORDINATOR &&
                    teacher.subRole != SubRole.ICT_ADMIN
                ) {
                    throw forbidden("Not your class")
                }
            }
            ?: throw notFound("Class not found")

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw forbidden("Teacher access only")
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)
}
