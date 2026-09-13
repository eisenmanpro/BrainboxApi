package com.afrithecus.brainbox.api.report.web

/**
 * Reporting-hub contract (docs/ongoing/api_reports_changes.md). Field names are
 * the client's wire names (Gson, enums by name, epoch millis). teacherId /
 * teacherName are accepted for wire compatibility but are never authoritative:
 * the actor is always derived from the bearer token.
 */
enum class ReportType {
    CBC_CLASS,
    CBC_STUDENT,
    TRADITIONAL_COMBINED,
    TRADITIONAL_STUDENT,
    TEACHER_PERFORMANCE,
    DETAILED_CBC_CLASS,
    DETAILED_CBC_STUDENT,
    TRADITIONAL_PER_CLASS_TABLES,
    TRADITIONAL_GRADE_ANALYSIS,
}

enum class ReportJobStatus { QUEUED, IN_PROGRESS, READY, FAILED }

data class ReportGenerationRequestPayload(
    val reportType: ReportType,
    val classId: String? = null,
    val studentIds: List<String> = emptyList(),
    val teacherId: String? = null,
    val teacherName: String? = null,
    val term: String = "",
    val year: Int? = null,
    val examId: String? = null,
    val metrics: List<String> = emptyList(),
    val jobRequestId: String? = null,
    val schoolId: String? = null,
)

data class ReportJobPayload(
    val jobId: String,
    val status: ReportJobStatus,
    val progress: Int = 0,
    val message: String? = null,
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val createdAt: Long,
    val completedAt: Long? = null,
    val pollAfterMillis: Long? = null,
)

data class ReportHistoryItemPayload(
    val id: String,
    val reportType: ReportType,
    val title: String,
    val fileName: String,
    val fileUrl: String,
    val fileSize: Long,
    val classId: String? = null,
    val term: String,
    val generatedAt: Long,
    val status: ReportJobStatus,
)

data class ReportSchedulePayload(
    val id: String,
    val title: String,
    val reportType: ReportType,
    val classId: String? = null,
    val term: String = "",
    val frequency: String,
    val destination: String,
    val enabled: Boolean = true,
    val nextRunAt: Long,
    val lastRunAt: Long? = null,
    val createdAt: Long,
)

data class ReportQuotaPayload(
    val limit: Int,
    val used: Int,
    val remaining: Int,
    val resetsAt: Long,
)
