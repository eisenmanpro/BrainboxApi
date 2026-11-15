package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportType

/**
 * One canonical source for report titles and descriptions (D6/F19).
 *
 * The renderer, the template downloads and [ReportGenerationService.titleOf] all read
 * from here, so a report can never be called "Combined" on one surface and "Combined
 * Grade Report" on another. Dynamic documents keep their exam/grade prefix:
 * [combined], [perClass] and [analysis].
 */
object ReportTitles {

    /** Static document title for aggregate/expanded report types. */
    fun title(type: ReportType): String = when (type) {
        ReportType.CBC_CLASS -> "Class CBC Report"
        ReportType.DETAILED_CBC_CLASS -> "Detailed Class CBC Report"
        ReportType.CBC_STUDENT -> "Student CBC Report"
        ReportType.DETAILED_CBC_STUDENT -> "Detailed Student CBC Report"
        ReportType.TRADITIONAL_COMBINED -> "Combined Grade Report"
        ReportType.TRADITIONAL_STUDENT -> "Student Traditional Exam Report"
        ReportType.TRADITIONAL_PER_CLASS_TABLES -> "Per Class Tables"
        ReportType.TRADITIONAL_GRADE_ANALYSIS -> "Grade Analysis"
        ReportType.TEACHER_PERFORMANCE -> "Teacher Performance Report"
    }

    /** Short description used by the report/template pickers. */
    fun description(type: ReportType): String = when (type) {
        ReportType.CBC_CLASS ->
            "Class CBC overview: strand mastery, subject-teacher impact and next-term focus."
        ReportType.DETAILED_CBC_CLASS ->
            "Class CBC overview plus a separate weak-strand recommendations page."
        ReportType.CBC_STUDENT ->
            "One learner's CBC card: learning areas and the latest published paper exam."
        ReportType.DETAILED_CBC_STUDENT ->
            "Two pages: the learner's CBC card plus engagement and learning-area analytics."
        ReportType.TRADITIONAL_COMBINED ->
            "Whole-grade ranked marks list with totals, means and students with incomplete marks."
        ReportType.TRADITIONAL_STUDENT ->
            "One learner's ranked exam card with subject levels, remarks and grading legend."
        ReportType.TRADITIONAL_PER_CLASS_TABLES ->
            "One ranked marks list per class; the download contains one PDF for each class."
        ReportType.TRADITIONAL_GRADE_ANALYSIS ->
            "Class comparison with a weighted grade mean and subject-teacher contribution charts."
        ReportType.TEACHER_PERFORMANCE ->
            "One teacher's subject delivery, strand impact, coverage and next step."
    }

    /** Prefix title for a full exam document (combined lists). */
    fun combined(examTitle: String): String = examTitle

    /** Title of one per-class list PDF. */
    fun perClass(examTitle: String): String = examTitle + " - Per Class Tables"

    /** Title of the grade analysis document for an exam. */
    fun analysis(gradeLevel: String): String = gradeLevel + " Grade Analysis"

    /** Blank-template download title ("Template - <report title>"). */
    fun templateTitle(type: ReportType): String = "Template - " + title(type)

    /**
     * Default orientation of the blank template shell. Dynamic list documents compute their
     * orientation from the subject count (A20); templates ship the common case: the dense
     * lists, analysis, class and teacher reports are landscape, the learner cards portrait.
     */
    fun landscape(type: ReportType): Boolean = when (type) {
        ReportType.CBC_STUDENT,
        ReportType.DETAILED_CBC_STUDENT,
        ReportType.TRADITIONAL_STUDENT,
        -> false

        ReportType.CBC_CLASS,
        ReportType.DETAILED_CBC_CLASS,
        ReportType.TEACHER_PERFORMANCE,
        ReportType.TRADITIONAL_COMBINED,
        ReportType.TRADITIONAL_PER_CLASS_TABLES,
        ReportType.TRADITIONAL_GRADE_ANALYSIS,
        -> true
    }
}
