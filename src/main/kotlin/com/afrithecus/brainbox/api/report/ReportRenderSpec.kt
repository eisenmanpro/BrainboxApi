package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.cbcratings.web.CbcClassReportPayload
import com.afrithecus.brainbox.api.cbcratings.web.CbcReportCardPayload
import com.afrithecus.brainbox.api.traditional.web.TraditionalExamDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalGradeAnalysisDto
import com.afrithecus.brainbox.api.traditional.web.TraditionalStudentReportDto
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
)

data class CbcStudentsSpec(
    override val title: String,
    override val branding: ReportBranding,
    val cards: List<CbcStudentCard>,
) : ReportRenderSpec

data class CbcClassSpec(
    override val title: String,
    override val branding: ReportBranding,
    val classReport: CbcClassReportPayload,
    /** When set (teacher-performance report) only this teacher's row is shown. */
    val focusTeacher: String? = null,
) : ReportRenderSpec
