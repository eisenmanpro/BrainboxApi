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

data class GradeTableSpec(
    override val title: String,
    override val branding: ReportBranding,
    val exam: TraditionalExamDto,
    val rows: List<StudentGradeRowDto>,
    /** Per-class tables render a section per class; grade-wide renders one class column. */
    val perClass: Boolean = false,
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
) : ReportRenderSpec

data class CbcStudentCard(
    val studentId: String,
    val studentName: String,
    val gradeLevel: String?,
    val term: String,
    val card: CbcReportCardPayload,
    /** Extra analytics rendered only on the detailed second page. */
    val detail: CbcStudentDetail? = null,
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
    /** When set (teacher-performance report) only this teacher's row is shown. */
    val focusTeacher: String? = null,
    /** DETAILED_CBC_CLASS renders weak-strand recommendations as a separate section. */
    val detailed: Boolean = false,
) : ReportRenderSpec

/** A blank, branded template (no report data) for the templates screen. */
data class TemplateSpec(
    override val title: String,
    override val branding: ReportBranding,
    val reportType: ReportType,
) : ReportRenderSpec
