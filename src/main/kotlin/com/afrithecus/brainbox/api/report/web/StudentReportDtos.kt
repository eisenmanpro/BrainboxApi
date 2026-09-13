package com.afrithecus.brainbox.api.report.web

/** Learner/parent report request (docs/ongoing/pdf_generator_cleanup.md section 3). */
data class StudentReportRequestPayload(
    val reportType: ReportType,
    val studentId: String? = null,
    val examId: String? = null,
    val term: String = "",
    val year: Int? = null,
    val jobRequestId: String? = null,
)
