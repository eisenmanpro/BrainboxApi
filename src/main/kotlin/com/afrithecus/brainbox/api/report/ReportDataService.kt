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
            GradeAnalysisSpec(ReportTitles.analysis(analysis.gradeLevel), branding, analysis)
        }
        ReportType.TRADITIONAL_STUDENT -> {
            val examId = requireExamId(request)
            val exam = traditional.getExam(actor, examId)
            val reports = request.studentIds.map { traditional.myResult(actor, examId, it) }
            if (reports.isEmpty()) throw invalidArgument("studentIds are required for a traditional student report")
            // D18: the card legend follows the capture session's resolved bands.
            val bands = exam.grading?.bands
                ?.map { ReportGradingBand(it.grade, it.minPercentage.toDouble()) }
                .orEmpty()
            TraditionalStudentsSpec(ReportTitles.title(ReportType.TRADITIONAL_STUDENT), branding, reports, bands)
        }
        ReportType.CBC_STUDENT, ReportType.DETAILED_CBC_STUDENT -> {
            val detailed = request.reportType == ReportType.DETAILED_CBC_STUDENT
            val card = cbcStudentCards(request, actor, detailed)
            CbcStudentsSpec(
                title = ReportTitles.title(request.reportType),
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
                title = ReportTitles.title(request.reportType),
                branding = branding,
                classReport = report,
                year = request.year,
                detailed = request.reportType == ReportType.DETAILED_CBC_CLASS,
            )
        }
        ReportType.TEACHER_PERFORMANCE -> teacherPerformanceSpec(request, actor, branding)
    }

    /**
     * One render task per artifact (D19). Per-class tables render one PDF per class: a
     * request with a class tag yields exactly that class, a blank tag yields every class
     * of the exam and the job bundles the files. Every other report type is a single PDF.
     */
    fun buildArtifacts(
        request: ReportGenerationRequestPayload,
        actor: CurrentUser,
        branding: ReportBranding,
    ): List<ReportArtifact> {
        if (request.reportType != ReportType.TRADITIONAL_PER_CLASS_TABLES) {
            return listOf(ReportArtifact(build(request, actor, branding)))
        }
        val examId = requireExamId(request)
        val exam = traditional.getExam(actor, examId)
        val rows = withAbsentRows(actor, exam, traditional.gradeWideRanking(actor, examId))
        val bands = exam.grading?.bands
            ?.map { ReportGradingBand(it.grade, it.minPercentage.toDouble()) }
            .orEmpty()
        val classTags = rows.map { it.classTag }.filter { it.isNotBlank() }.distinct().sorted()
        val requested = request.classTag?.trim()?.takeIf { it.isNotEmpty() }
        val selected = if (requested == null) {
            classTags
        } else {
            classTags.filter { it.equals(requested, ignoreCase = true) }
        }
        if (selected.isEmpty()) {
            throw invalidArgument("classTag is not one of this exam's classes")
        }
        val title = ReportTitles.perClass(exam.title)
        val classTeachers = runCatching { traditional.classTeachers(actor, examId) }.getOrDefault(emptyMap())
        return selected.map { classTag ->
            val classRows = rows.filter { it.classTag.equals(classTag, ignoreCase = true) }
            ReportArtifact(
                spec = GradeTableSpec(
                    title = title,
                    branding = branding,
                    exam = exam,
                    rows = classRows,
                    perClass = true,
                    bands = bands,
                    classTeachers = classTeachers,
                ),
                classTag = classTag,
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
        val title = if (perClass) ReportTitles.perClass(exam.title) else ReportTitles.combined(exam.title)
        // D18: the lists print the bands resolved when the capture session was created, not
        // whatever the grade setting happens to be at download time.
        val bands = exam.grading?.bands
            ?.map { ReportGradingBand(it.grade, it.minPercentage.toDouble()) }
            .orEmpty()
        // D11/F25: the class teacher on the per-class header comes from the class row, resolved
        // by the traditional service; raw class tags match StudentGradeRowDto.classTag.
        val classTeachers = runCatching { traditional.classTeachers(actor, examId) }.getOrDefault(emptyMap())
        return GradeTableSpec(title, branding, exam, ranked, perClass, bands, classTeachers)
    }

    private fun cbcStudentCards(request: ReportGenerationRequestPayload, actor: CurrentUser, detailed: Boolean): List<CbcStudentCard> {
        if (request.studentIds.isEmpty()) throw invalidArgument("studentIds are required for a CBC student report")
        val viewer = userRepository.findById(actor.userId).orElseThrow { invalidArgument("Report owner not found") }
        return request.studentIds.map { studentId ->
            val card = cbc.studentReportForViewer(viewer, studentId, request.term)
            CbcStudentCard(
                studentId = studentId,
                studentName = card.studentName,
                gradeLevel = card.gradeLevel,
                term = card.term,
                card = card,
                detail = detailFor(viewer, studentId, detailed),
                year = request.year ?: java.time.LocalDate.now().year,
            )
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
            // 0.0 means "no submissions in this class", not a real class mean (F48) - render it
            // as not recorded rather than telling a parent the class averaged 0%.
            classAverageScore = analytics.classComparisons?.classAverageScore?.takeIf { it > 0.0 },
            classPercentile = analytics.classComparisons?.studentPercentile?.takeIf { it > 0 },
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
        // The session snapshot is authoritative for this exam (D18); the live grade setting is
        // only a fallback for legacy exams that predate the snapshot.
        val grading = exam.grading
            ?: runCatching { traditional.gradingConfig(actor, exam.gradeLevel, null) }.getOrNull()
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

    /**
     * D4/F10: the teacher performance document is built from the **target teacher's** subject rows
     * in the class report, never by filtering the class view: averages, strand scores and coverage
     * must describe one teacher. The target is the requested teacher name when the coordinator/
     * ICT admin supplies one, otherwise the actor; the class scope (and therefore the grade scope)
     * is already enforced by ReportGenerationService, so a coordinator can inspect a colleague in
     * their own grade while the actor's token still owns the job. A teacher with no rows in this
     * class renders a valid empty report (zero figures, explicit note) rather than someone else's
     * numbers.
     */
    private fun teacherPerformanceSpec(
        request: ReportGenerationRequestPayload,
        actor: CurrentUser,
        branding: ReportBranding,
    ): TeacherPerformanceSpec {
        val requester = teacher(actor)
        val classId = request.classId ?: throw invalidArgument("classId is required for a teacher performance report")
        val report = cbc.classReport(requester, classId, request.term)
        val targetName = request.teacherName?.trim()?.takeIf { it.isNotEmpty() } ?: requester.name.trim()
        val mine = report.subjectTeacherPerformance.filter { normalizeName(it.teacherName) == normalizeName(targetName) }
        val teacherName = mine.firstOrNull()?.teacherName?.trim()?.takeIf { it.isNotEmpty() } ?: targetName
        val strandNames = report.strandMastery.associate { it.strandCode to it.strandName }
        val subjects = mine.map { row ->
            TeacherSubjectPerformance(
                subject = row.subject,
                average = row.classAverage,
                learners = row.studentCount,
                strands = row.strandScores.map { (code, score) ->
                    TeacherStrandScore(code = code, name = strandNames[code] ?: code, percentage = score)
                },
            )
        }
        val teacherAverage = if (subjects.isEmpty()) 0.0 else subjects.map { it.average }.average()
        val top = subjects.maxByOrNull { it.average }
        return TeacherPerformanceSpec(
            title = ReportTitles.title(ReportType.TEACHER_PERFORMANCE),
            branding = branding,
            teacherName = teacherName,
            className = report.className,
            term = report.term,
            year = request.year,
            teacherAverage = teacherAverage,
            classAverage = report.overallClassAverage,
            subjects = subjects,
            subjectsInClass = report.subjectTeacherPerformance.map { it.subject }.filter { it.isNotBlank() }.distinct().size,
            learners = mine.maxOfOrNull { it.studentCount } ?: report.studentCount,
            topSubject = top?.subject,
            topSubjectAverage = top?.average ?: 0.0,
        )
    }

    /** Trim/case/whitespace-insensitive teacher-name match (D14-style normalisation). */
    private fun normalizeName(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ").removeSuffix(".").removeSuffix(" ")

    private fun teacher(actor: CurrentUser) = userRepository.findById(actor.userId).orElse(null)
        ?: throw invalidArgument("Report owner not found")

    private fun requireExamId(request: ReportGenerationRequestPayload): String =
        request.examId?.takeIf { it.isNotBlank() } ?: throw invalidArgument("examId is required for a traditional report")
}

/**
 * A single PDF the job must render. [classTag] is set for per-class tables so the renderer
 * renders exactly that class; the job names and (when there are several) bundles the files.
 */
data class ReportArtifact(
    val spec: ReportRenderSpec,
    val classTag: String? = null,
)
