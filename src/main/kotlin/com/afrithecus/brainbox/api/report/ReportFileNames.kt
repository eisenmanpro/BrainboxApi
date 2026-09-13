package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload

/** Deterministic, filesystem-safe report file names (mirrors the client mock). */
object ReportFileNames {

    fun forRequest(request: ReportGenerationRequestPayload): String {
        val scope = request.classId ?: request.examId ?: request.studentIds.firstOrNull() ?: "report"
        val raw = request.reportType.name.lowercase() + "_" + scope + "_" + request.term
        val base = raw.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "report" }
        return base + ".pdf"
    }
}
