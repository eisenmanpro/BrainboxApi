package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
import com.afrithecus.brainbox.api.report.web.ReportType
import com.afrithecus.brainbox.api.traditional.web.StudentGradeRowDto

/** A PDF the server must render, carrying the already-assembled report data. */
sealed interface ReportRenderSpec {
    val title: String
    val branding: ReportBranding
}

/** One resolved grading level for the legend, from the exam's session snapshot (D18). */
data class ReportGradingBand(val label: String, val minPercentage: Double)

data class GradeTableSpec(
    override val title: String,
    override val branding: ReportBranding,
    val exam: TraditionalExamDto,
    val rows: List<StudentGradeRowDto>,
    /** Per-class tables render a section per class; grade-wide renders one class column. */
    val perClass: Boolean = false,
    /** Session-resolved grading bands (D18) used for the legend; empty = platform defaults. */
    val bands: List<ReportGradingBand> = emptyList(),
    /** Class tag -> class teacher name (D11), shown in the per-class header. */
    val classTeachers: Map<String, String> = emptyMap(),
) : ReportRenderSpec

data class GradeAnalysisSpec(
    override val title: String,
    override val branding: ReportBranding,
    val analysis: TraditionalGradeAnalysisDto,
) : ReportRenderSpec

data class TraditionalStudentsSpec(
    override val title: String,
    override val branding: ReportBranding,
    val reports: List<TraditionalStudentReportDto>,
    /** Session-resolved grading bands (D18) for the card legend; empty = v1 default labels. */
    val bands: List<ReportGradingBand> = emptyList(),
) : ReportRenderSpec

data class CbcStudentCard(
    val studentId: String,
    val studentName: String,
    val gradeLevel: String?,
    val term: String,
    val card: CbcReportCardPayload,
    /** Extra analytics rendered only on the detailed second page. */
    val detail: CbcStudentDetail? = null,
    /** Academic year shown in the card header; null falls back to the request year. */
    val year: Int? = null,
)

/** Engagement data for the detailed CBC student page; every field is optional. */
data class CbcStudentDetail(
    val learningStreakDays: Int = 0,
    val totalXp: Int = 0,
    val classAverageScore: Double? = null,
    val classPercentile: Int? = null,
)

data class CbcStudentsSpec(
    override val title: String,
    override val branding: ReportBranding,
    val cards: List<CbcStudentCard>,
    /** DETAILED_CBC_STUDENT adds a second analytics page per learner. */
    val detailed: Boolean = false,
) : ReportRenderSpec

data class CbcClassSpec(
    override val title: String,
    override val branding: ReportBranding,
    val classReport: CbcClassReportPayload,
    /** Academic year shown in the landscape header; null renders the term box only. */
    val year: Int? = null,
    /** DETAILED_CBC_CLASS renders weak-strand recommendations as a separate section. */
    val detailed: Boolean = false,
) : ReportRenderSpec

/** One strand achieved by the teacher's taught subject (A6/D4). */
data class TeacherStrandScore(
    val code: String,
    /** Learning-area name joined from the class report when available (else the code). */
    val name: String,
    val percentage: Double,
)

/** One subject the teacher delivers in this class (A6/D4). */
data class TeacherSubjectPerformance(
    val subject: String,
    val average: Double,
    val learners: Int,
    val strands: List<TeacherStrandScore> = emptyList(),
)

/**
 * TEACHER_PERFORMANCE spec (type 4, D4/F10).
 *
 * Unlike [CbcClassSpec], every figure here is **teacher-specific**: the averages and strand
 * scores are the ones achieved by the named teacher in the taught subjects, and the class
 * average is the class-level comparison baseline. Built by ReportDataService from
 * CbcAnalyticsService.classReport (subjectTeacherPerformance + strandMastery); the renderer
 * never re-derives class-wide numbers into "the teacher's" performance.
 */
data class TeacherPerformanceSpec(
    override val title: String,
    override val branding: ReportBranding,
    val teacherName: String,
    val className: String,
    val term: String,
    val year: Int?,
    /** Mean of the teacher's taught-subject averages. */
    val teacherAverage: Double,
    /** Whole-class average, used only for the teacher-vs-class comparison. */
    val classAverage: Double,
    /** Distinct subjects the teacher teaches in this class. */
    val subjects: List<TeacherSubjectPerformance>,
    /** Distinct subjects taught in the class by any teacher (coverage denominator). */
    val subjectsInClass: Int,
    val learners: Int,
    val topSubject: String?,
    val topSubjectAverage: Double,
) : ReportRenderSpec

/** A blank, branded template (no report data) for the templates screen. */
data class TemplateSpec(
    override val title: String,
    override val branding: ReportBranding,
    val reportType: ReportType,
) : ReportRenderSpec
