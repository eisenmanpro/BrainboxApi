package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.cbcratings.CbcAnalyticsService
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.studentanalytics.StudentAnalyticsService
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.TraditionalExamService
import com.afrithecus.brainbox.api.traditional.model.TraditionalSubjectType
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalSubjectResultDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Assembles the render-ready report data from the existing, authorization-checked
 * domain services (traditional exam engine, CBC analytics) so the renderer stays
 * pure and the report never invents data the learner cannot see.
 */
@Service
class ReportDataService(
    private val traditional: TraditionalExamService,
    private val cbc: CbcAnalyticsService,
    private val studentAnalyticsService: StudentAnalyticsService,
    private val userRepository: UserRepository,
    private val membershipRepository: ClassMembershipRepository,
) {

    @Transactional(readOnly = true)
    fun build(
        request: ReportGenerationRequestPayload,
        actor: CurrentUser,
        branding: ReportBranding,
    ): ReportRenderSpec = when (request.reportType) {
        ReportType.TRADITIONAL_COMBINED ->
            gradeTableSpec(request, actor, branding, perClass = false)
        ReportType.TRADITIONAL_PER_CLASS_TABLES ->
            gradeTableSpec(request, actor, branding, perClass = true)
        ReportType.TRADITIONAL_GRADE_ANALYSIS -> {
            val examId = requireExamId(request)
            val analysis = traditional.gradeAnalysis(actor, examId)
            GradeAnalysisSpec(analysis.gradeLevel + " Grade Analysis", branding, analysis)
        }
        ReportType.TRADITIONAL_STUDENT -> {
            val examId = requireExamId(request)
            val reports = request.studentIds.map { traditional.myResult(actor, examId, it) }
            if (reports.isEmpty()) throw invalidArgument("studentIds are required for a traditional student report")
            TraditionalStudentsSpec("Student Traditional Exam Report", branding, reports)
        }
        ReportType.CBC_STUDENT, ReportType.DETAILED_CBC_STUDENT -> {
            val detailed = request.reportType == ReportType.DETAILED_CBC_STUDENT
            val card = cbcStudentCards(request, actor, detailed)
            CbcStudentsSpec(
                title = if (detailed) "Detailed Student CBC Report" else "Student CBC Report",
                branding = branding,
                cards = card,
                detailed = detailed,
            )
        }
        ReportType.CBC_CLASS, ReportType.DETAILED_CBC_CLASS -> {
            val teacher = teacher(actor)
            val classId = request.classId ?: throw invalidArgument("classId is required for a CBC class report")
            val report = cbc.classReport(teacher, classId, request.term)
            CbcClassSpec(
                title = if (request.reportType == ReportType.DETAILED_CBC_CLASS) "Detailed Class CBC Report" else "Class CBC Report",
                branding = branding,
                classReport = report,
                detailed = request.reportType == ReportType.DETAILED_CBC_CLASS,
            )
        }
        ReportType.TEACHER_PERFORMANCE -> {
            val teacher = teacher(actor)
            val classId = request.classId ?: throw invalidArgument("classId is required for a teacher performance report")
            val report = cbc.classReport(teacher, classId, request.term)
            CbcClassSpec(
                title = "Teacher Performance Report",
                branding = branding,
                classReport = report,
                focusTeacher = request.teacherName?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Rough row/student count driving the server-side sync/async decision. */
    fun estimate(request: ReportGenerationRequestPayload, actor: CurrentUser): Int = when (request.reportType) {
        ReportType.TRADITIONAL_COMBINED, ReportType.TRADITIONAL_PER_CLASS_TABLES,
        ReportType.TRADITIONAL_GRADE_ANALYSIS ->
            runCatching { traditional.students(actor, request.examId ?: return 0, null).size }.getOrDefault(0)
        ReportType.TRADITIONAL_STUDENT, ReportType.CBC_STUDENT, ReportType.DETAILED_CBC_STUDENT ->
            request.studentIds.size
        ReportType.CBC_CLASS, ReportType.DETAILED_CBC_CLASS, ReportType.TEACHER_PERFORMANCE ->
            runCatching { membershipRepository.countByClassId(UUID.fromString(request.classId)).toInt() }.getOrDefault(0)
    }

    // ------------------------------------------------------------ internals

    private fun gradeTableSpec(
        request: ReportGenerationRequestPayload,
        actor: CurrentUser,
        branding: ReportBranding,
        perClass: Boolean,
    ): GradeTableSpec {
        val examId = requireExamId(request)
        val exam = traditional.getExam(actor, examId)
        val ranked = withAbsentRows(actor, exam, traditional.gradeWideRanking(actor, examId))
        val title = if (perClass) exam.title + " - Per Class Tables" else exam.title
        return GradeTableSpec(title, branding, exam, ranked, perClass)
    }

    private fun cbcStudentCards(request: ReportGenerationRequestPayload, actor: CurrentUser, detailed: Boolean): List<CbcStudentCard> {
        if (request.studentIds.isEmpty()) throw invalidArgument("studentIds are required for a CBC student report")
        val viewer = userRepository.findById(actor.userId).orElseThrow { invalidArgument("Report owner not found") }
        return request.studentIds.map { studentId ->
            val card = cbc.studentReportForViewer(viewer, studentId, request.term)
            CbcStudentCard(studentId, card.studentName, card.gradeLevel, card.term, card, detailFor(viewer, studentId, detailed))
        }
    }

    /** Engagement analytics for the detailed page; teacher-scoped and best-effort. */
    private fun detailFor(viewer: UserEntity, studentId: String, detailed: Boolean): CbcStudentDetail? {
        if (!detailed || viewer.role != Role.TEACHER) return null
        val analytics = runCatching { studentAnalyticsService.studentAnalytics(viewer, studentId) }.getOrNull()
            ?: return null
        return CbcStudentDetail(
            learningStreakDays = analytics.quickStats.streakDays,
            totalXp = analytics.quickStats.totalXP,
            classAverageScore = analytics.classComparisons?.classAverageScore,
            classPercentile = analytics.classComparisons?.studentPercentile,
        )
    }

    /** Appends rostered students with no marks as zero rows, mirroring the client. */
    private fun withAbsentRows(
        actor: CurrentUser,
        exam: TraditionalExamDto,
        rows: List<StudentGradeRowDto>,
    ): List<StudentGradeRowDto> {
        val present = rows.map { it.studentId }.toSet()
        val roster = runCatching { traditional.students(actor, exam.examId, exam.gradeLevel) }.getOrDefault(emptyList())
        val grading = runCatching { traditional.gradingConfig(actor, exam.gradeLevel, null) }.getOrNull()
        val zeroGrade = grading?.bands?.minByOrNull { it.minPercentage }?.grade
            ?: grading?.bands?.lastOrNull()?.grade ?: "-"
        val overallZero = grading?.overallBands?.minByOrNull { it.minRawScore }?.grade ?: zeroGrade
        val absent = roster.filter { it.id !in present }.map { student ->
            StudentGradeRowDto(
                rank = 0,
                studentId = student.id,
                studentName = student.name,
                classTag = student.className.ifBlank { exam.gradeLevel },
                subjects = exam.subjects.map { subject ->
                    TraditionalSubjectResultDto(
                        subjectName = subject.name,
                        rawScore = 0,
                        maxScore = subject.maxScore,
                        percentage = 0.0,
                        grade = zeroGrade,
                        isCombined = subject.type == TraditionalSubjectType.COMBINED,
                    )
                },
                totalPercentage = 0.0,
                totalScore = 0,
                overallGrade = overallZero,
            )
        }
        return (rows + absent).sortedByDescending { it.totalPercentage }.mapIndexed { index, row -> row.copy(rank = index + 1) }
    }

    private fun teacher(actor: CurrentUser) = userRepository.findById(actor.userId).orElse(null)
        ?: throw invalidArgument("Report owner not found")

    private fun requireExamId(request: ReportGenerationRequestPayload): String =
        request.examId?.takeIf { it.isNotBlank() } ?: throw invalidArgument("examId is required for a traditional report")
}
