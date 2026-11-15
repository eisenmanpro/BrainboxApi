package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload

/** Deterministic, filesystem-safe report file names (mirrors the client mock). */
object ReportFileNames {

    fun forRequest(request: ReportGenerationRequestPayload): String {
        val scope = request.classId ?: request.examId ?: request.studentIds.firstOrNull() ?: "report"
        val raw = request.reportType.name.lowercase() + "_" + scope + "_" + request.term
        return safe(raw) + ".pdf"
    }

    /**
     * One class inside a per-class bundle (D19). The class tag is part of the name so a
     * downloaded zip is self-describing: `5_Lion_end_term_2_2026.pdf`.
     */
    fun forClass(classTag: String, examTitle: String, term: String): String {
        val raw = classTag + "_" + examTitle + "_" + term
        return safe(raw) + ".pdf"
    }

    /** The single artifact of a per-class ALL job: `per_class_tables_<exam>_<term>.zip`. */
    fun forBundle(examTitle: String, term: String): String =
        safe("per_class_tables_" + examTitle + "_" + term) + ".zip"

    private fun safe(raw: String): String {
        val base = raw.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        return base.ifBlank { "report" }.take(180)
    }
}
