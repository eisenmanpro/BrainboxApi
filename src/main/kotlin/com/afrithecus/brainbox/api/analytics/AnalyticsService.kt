package com.afrithecus.brainbox.api.analytics

import com.afrithecus.brainbox.api.analytics.web.AnalyticsExamResultPayload
import com.afrithecus.brainbox.api.analytics.web.AnalyticsTopicPerformancePayload
import com.afrithecus.brainbox.api.analytics.web.ClassAnalyticsPayload
import com.afrithecus.brainbox.api.analytics.web.StudentGradePayload
import com.afrithecus.brainbox.api.analytics.web.StudentPayload
import com.afrithecus.brainbox.api.analytics.web.StudentPerformancePayload
import com.afrithecus.brainbox.api.analytics.web.SubjectClassPerformancePayload
import com.afrithecus.brainbox.api.analytics.web.SubjectDetailPayload
import com.afrithecus.brainbox.api.analytics.web.SubjectPerformancePayload
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSubmissionEntity
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Server-computed exam analytics (doc 02 §8). Every number the client renders is
 * produced here; scores, rankings and distributions are never trusted from the
 * device. Reads are authorized by relationship: self, linked parent, own-class
 * teacher, or school-scoped staff.
 */
@Service
class AnalyticsService(
    private val examRepository: ExamRepository,
    private val submissionRepository: ExamSubmissionRepository,
    private val questionRepository: ExamQuestionRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val classRepository: TeacherClassRepository,
    private val assembler: ExamResultAssembler,
) {

    @Transactional(readOnly = true)
    fun studentPerformance(current: CurrentUser, studentIdRaw: String): StudentPerformancePayload {
        val student = findUser(studentIdRaw)
        authorizeStudentViewer(current, student)
        val dataset = dataset(submissionRepository.findAllByUserId(student.id))
        val attempts = dataset.attempts
        val bySubject = attempts.groupBy { it.subject }
        val subjects = bySubject.map { (subject, list) ->
            val mean = mean(list.map { it.percentage })
            SubjectPerformancePayload(
                subject = subject,
                score = mean,
                grade = CbcGrades.grade(mean),
                percentage = mean,
                trend = list.sortedBy { it.submittedAt }.map { CbcGrades.round2(it.percentage) },
            )
        }.sortedByDescending { it.percentage }

        val overall = if (attempts.isEmpty()) 0.0 else mean(attempts.map { it.percentage })
        val previous = if (attempts.size >= 2) {
            mean(attempts.sortedBy { it.submittedAt }.dropLast(1).map { it.percentage })
        } else {
            null
        }
        val strengths = subjects.filter { it.percentage >= 65.0 }.take(3).map { it.subject }
        val weaknesses = subjects.filter { it.percentage < 65.0 }.sortedBy { it.percentage }.take(3).map { it.subject }
        return StudentPerformancePayload(
            student = StudentPayload(
                id = student.id.toString(),
                name = student.name,
                className = classNameOf(student.id),
                admissionNumber = student.studentAdmissionNumber ?: "",
            ),
            overallPercentage = overall,
            overallGrade = CbcGrades.grade(overall),
            previousPercentage = previous,
            subjects = subjects,
            strengths = strengths,
            weaknesses = weaknesses,
            recommendedActions = recommendations(weaknesses),
            examHistory = history(attempts, dataset),
        )
    }

    @Transactional(readOnly = true)
    fun subjectDetail(current: CurrentUser, studentIdRaw: String, subjectRaw: String): SubjectDetailPayload {
        val subject = subjectRaw.trim()
        if (subject.isEmpty()) throw invalidArgument("subject is required")
        val student = findUser(studentIdRaw)
        authorizeStudentViewer(current, student)
        val dataset = dataset(submissionRepository.findAllByUserId(student.id))
        val attempts = dataset.attempts.filter { it.subject.equals(subject, ignoreCase = true) }
        val overall = if (attempts.isEmpty()) 0.0 else mean(attempts.map { it.percentage })

        val earned = LinkedHashMap<String, Int>()
        val possible = LinkedHashMap<String, Int>()
        val questionsBy = dataset.questionsByExam
        for (attempt in attempts) {
            for (entry in assembler.topicTally(attempt.questionResultsJson, questionsBy[attempt.examId].orEmpty())) {
                earned[entry.topic] = (earned[entry.topic] ?: 0) + entry.earned
                possible[entry.topic] = (possible[entry.topic] ?: 0) + entry.possible
            }
        }
        val topics = possible.map { (topic, total) ->
            val pct = CbcGrades.round2(if (total <= 0) 0.0 else (earned[topic] ?: 0) * 100.0 / total)
            AnalyticsTopicPerformancePayload(topic = topic, score = pct, masteryLevel = CbcGrades.mastery(pct))
        }.sortedByDescending { it.score }
        val weakTopics = topics.filter { it.score < 65.0 }.sortedBy { it.score }.take(3).map { it.topic }
        return SubjectDetailPayload(
            studentId = student.id.toString(),
            subject = attempts.firstOrNull()?.subject ?: subject,
            overallGrade = CbcGrades.grade(overall),
            overallScore = overall,
            examHistory = history(attempts, dataset),
            topicBreakdown = topics,
            recommendedActions = recommendations(weakTopics),
        )
    }

    @Transactional(readOnly = true)
    fun classAnalytics(current: CurrentUser, classIdRaw: String): ClassAnalyticsPayload {
        val classId = parseUuid(classIdRaw, "class id")
        val clazz = classRepository.findById(classId).orElse(null) ?: throw notFound("Class not found")
        authorizeClassViewer(current, clazz)

        val studentIds = membershipRepository.findAllByClassId(classId).map { it.studentId }
        val students = userRepository.findAllById(studentIds)
        val dataset = dataset(submissionRepository.findAllByUserIdIn(studentIds))
        val byStudent = dataset.attempts.groupBy { it.studentId }

        val rows = students.map { student ->
            val mine = byStudent[student.id].orEmpty()
            val overall = if (mine.isEmpty()) 0.0 else mean(mine.map { it.percentage })
            StudentGradePayload(
                studentId = student.id.toString(),
                studentName = student.name,
                overallPercentage = overall,
                overallGrade = CbcGrades.grade(overall),
                rank = null,
            )
        }.sortedByDescending { it.overallPercentage }
            .mapIndexed { index, row -> row.copy(rank = if (row.overallPercentage > 0.0) index + 1 else null) }

        val distribution = linkedMapOf("EE" to 0, "ME" to 0, "AE" to 0, "BE" to 0)
        rows.forEach { row -> distribution[row.overallGrade] = (distribution[row.overallGrade] ?: 0) + 1 }

        val classAverage = if (rows.isEmpty()) 0.0 else mean(rows.map { it.overallPercentage })
        val subjectPerformance = dataset.attempts.groupBy { it.subject }.map { (subject, list) ->
            val percentages = list.map { it.percentage }
            SubjectClassPerformancePayload(
                subject = subject,
                classMean = mean(percentages),
                topScore = percentages.max().roundToInt(),
                bottomScore = percentages.min().roundToInt(),
                passRate = CbcGrades.round2(100.0 * percentages.count { it >= 50.0 } / percentages.size),
            )
        }.sortedByDescending { it.classMean }

        val examHistory = dataset.attempts.groupBy { it.examId }.map { (examId, list) ->
            val title = list.first().title
            val classMean = mean(list.map { it.percentage })
            assembler.toClassResult(
                examId = examId,
                title = title,
                attempts = list,
                questions = dataset.questionsByExam[examId].orEmpty(),
                percentile = percentileRank(dataset.percentagesByExam[examId].orEmpty(), classMean),
            )
        }.sortedByDescending { it -> attemptsLatest(dataset, it.id) }.take(10)

        return ClassAnalyticsPayload(
            classId = clazz.id.toString(),
            className = clazz.name,
            totalStudents = students.size,
            classAverage = classAverage,
            gradeDistribution = distribution,
            subjectPerformance = subjectPerformance,
            students = rows,
            examHistory = examHistory,
        )
    }

    // ------------------------------------------------------------ internals

    private class Dataset(
        val attempts: List<Attempt>,
        val questionsByExam: Map<UUID, List<ExamQuestionEntity>>,
        val percentile: Map<UUID, Int>,
        val percentagesByExam: Map<UUID, List<Int>>,
    )

    private fun dataset(submissions: List<ExamSubmissionEntity>): Dataset {
        val examIds = submissions.map { it.examId }.distinct()
        val exams = examRepository.findAllById(examIds).associateBy { it.id }
        val questionsByExam = questionRepository.findAllByExamIdIn(examIds).groupBy { it.examId }
        val allByExam = submissionRepository.findAllByExamIdIn(examIds).groupBy { it.examId }
        val percentile = submissions.associate { submission ->
            val group = allByExam[submission.examId] ?: listOf(submission)
            submission.id to percentileRank(group.map { it.percentage }, submission.percentage.toDouble())
        }
        val attempts = submissions.mapNotNull { submission ->
            val exam = exams[submission.examId] ?: return@mapNotNull null
            Attempt(
                submissionId = submission.id,
                studentId = submission.userId,
                examId = exam.id,
                title = exam.title,
                subject = exam.subject,
                score = submission.score,
                totalPoints = submission.totalPoints,
                percentage = submission.percentage.toDouble(),
                submittedAt = submission.submittedAt,
                questionResultsJson = submission.questionResults,
            )
        }
        return Dataset(
            attempts = attempts,
            questionsByExam = questionsByExam,
            percentile = percentile,
            percentagesByExam = allByExam.mapValues { (_, list) -> list.map { it.percentage } },
        )
    }

    private fun history(attempts: List<Attempt>, dataset: Dataset): List<AnalyticsExamResultPayload> =
        attempts.sortedByDescending { it.submittedAt }.take(20).map { attempt ->
            assembler.toResult(
                attempt = attempt,
                questions = dataset.questionsByExam[attempt.examId].orEmpty(),
                percentile = dataset.percentile[attempt.submissionId] ?: 0,
            )
        }

    private fun attemptsLatest(dataset: Dataset, examIdRaw: String): Long {
        val id = runCatching { UUID.fromString(examIdRaw) }.getOrNull() ?: return 0L
        return dataset.attempts.filter { it.examId == id }.maxOfOrNull { it.submittedAt.toEpochMilli() } ?: 0L
    }

    private fun classNameOf(studentId: UUID): String {
        val classId = membershipRepository.findAllByStudentId(studentId).firstOrNull()?.classId ?: return ""
        return classRepository.findById(classId).map { it.name }.orElse("")
    }

    private fun recommendations(weak: List<String>): List<String> =
        if (weak.isEmpty()) listOf("Keep practising to stay on track") else weak.map { "Revise " + it }

    private fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else CbcGrades.round2(values.average())

    private fun percentileRank(values: List<Int>, value: Double): Int {
        if (values.isEmpty()) return 0
        val atOrBelow = values.count { it <= value }
        return (100.0 * atOrBelow / values.size).roundToInt().coerceIn(0, 100)
    }

    private fun findUser(raw: String): UserEntity {
        val id = parseUuid(raw, "student id")
        return userRepository.findById(id).orElse(null) ?: throw notFound("Student not found")
    }

    private fun parseUuid(raw: String, label: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw invalidArgument("$label is not a valid identifier")

    private fun authorizeStudentViewer(current: CurrentUser, student: UserEntity) {
        if (current.userId == student.id) return
        val viewer = userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
        when (current.role) {
            Role.PARENT -> if (student.parentUserId != current.userId) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Not your linked child")
            }
            Role.TEACHER -> {
                if (current.subRole == SubRole.ICT_ADMIN) {
                    requireSameSchool(viewer, student)
                } else {
                    val studentClasses = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
                    val owned = classRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(current.userId)
                        .map { it.id }.toSet()
                    if (studentClasses.intersect(owned).isEmpty()) {
                        throw ApiException(ApiErrorCode.FORBIDDEN, "Student is not in your classes")
                    }
                }
            }
            Role.ADMIN -> requireSameSchool(viewer, student)
            Role.STUDENT -> throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot view another student's analytics")
        }
    }

    private fun authorizeClassViewer(current: CurrentUser, clazz: TeacherClassEntity) {
        if (current.role == Role.ADMIN) return
        if (current.role != Role.TEACHER) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Class analytics are staff-only")
        }
        if (clazz.teacherUserId == current.userId) return
        val viewer = userRepository.findById(current.userId).orElseThrow { notFound("User not found") }
        val staff = current.subRole == SubRole.GRADE_COORDINATOR || current.subRole == SubRole.ICT_ADMIN
        if (!staff) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        if (viewer.schoolId == null || clazz.schoolId == null || viewer.schoolId != clazz.schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Class is not in your school")
        }
    }

    private fun requireSameSchool(viewer: UserEntity, target: UserEntity) {
        if (viewer.schoolId == null || target.schoolId == null || viewer.schoolId != target.schoolId) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not in your school")
        }
    }
}
