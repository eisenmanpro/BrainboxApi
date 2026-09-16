package com.afrithecus.brainbox.api.ops.web

// ---------------------------------------------------------------------------
// O1 operations-backend payloads. GET /admin/ops/* is ADMIN-only and is the
// contract a future internal ops frontend consumes. Responses are deliberately
// flat and cheap: coarse dimensions only, never one field per topic.
// ---------------------------------------------------------------------------

/** Overall shelf coverage across every subject and grade. */
data class OpsCoverageOverall(
    /** Leaf topics on the shelf. */
    val totalTopics: Long,
    /** Leaf topics with at least one published notes or quiz unit. */
    val coveredTopics: Long,
    /** coveredTopics / totalTopics, or null when the shelf has no leaf topics. */
    val coverageRate: Double?,
)

/** Coverage for one subject x grade pair. */
data class OpsCoverageRow(
    val subject: String,
    val grade: String,
    val totalTopics: Long,
    val coveredTopics: Long,
    val practicePapersPublished: Long,
    val studyGuidesPublished: Long,
)

/** Response of GET /admin/ops/coverage. */
data class OpsCoveragePayload(
    val overall: OpsCoverageOverall,
    val rows: List<OpsCoverageRow>,
)

/**
 * Live + latest rolled-up values for the ops dashboard. "Today" is the current
 * UTC day; rolled-up figures are the previous complete hour (the scheduled job).
 */
data class OpsSummary(
    val generatedAt: Long,
    val coverage: OpsCoverageOverall,
    val coverageBySubjectGrade: List<OpsCoverageRow>,
    /** generation_jobs count per status, every canonical status present. */
    val queueDepth: Map<String, Long>,
    /** generation_jobs count per source and status, every canonical source present. */
    val queueDepthBySource: Map<String, Map<String, Long>>,
    /** Age in seconds of the oldest still-QUEUED job, or null when the queue is empty. */
    val oldestQueuedAgeSeconds: Long?,
    /** Units auto-approved by the machine since the start of the UTC day. */
    val autoApprovedToday: Long,
    /** Units created today that failed a gate and await a human (the exception queue). */
    val exceptionUnitsToday: Long,
    /** autoApprovedToday / (autoApprovedToday + exceptionUnitsToday), null when no attempts. */
    val autoApprovalRate: Double?,
    /** Latest rolled-up exception count per gate reason (previous complete hour). */
    val autoApprovalReasonMix: Map<String, Long>,
    /** Effective platform-wide daily job budget; <= 0 means unlimited. */
    val generationBudgetLimit: Int,
    val generationBudgetUsed: Long,
    /** limit - used, or null when the budget is unlimited. */
    val generationBudgetRemaining: Int?,
    val providerCallsToday: Long,
    val providerErrorsToday: Long,
    /** providerErrorsToday / providerCallsToday, null when there were no calls. */
    val providerErrorRate: Double?,
    /** Mean provider call latency today in milliseconds, null when there were no calls. */
    val averageGenerationLatencyMs: Double?,
    val promptTokensToday: Long,
    val completionTokensToday: Long,
    val costMicrosToday: Long,
    /** Units that resolved to REVIEWED today. */
    val publishedUnitsToday: Long,
    /** costMicrosToday / publishedUnitsToday, null when nothing published today. */
    val costPerPublishedItemMicros: Double?,
    /** Newest bucket present in ops_metric_rollup, null before the first rollup. */
    val latestRollupBucket: Long?,
)

/** One point of a rolled-up series. */
data class OpsTimeseriesPoint(
    val bucketStart: Long,
    val metric: String,
    val dimension: String,
    val value: Double,
)

/** Response of GET /admin/ops/timeseries. */
data class OpsTimeseriesPayload(
    val metric: String,
    val dimension: String?,
    val from: Long,
    val to: Long,
    /** The hard cap on returned points, so the caller can detect a truncated series. */
    val limit: Int,
    val truncated: Boolean,
    val points: List<OpsTimeseriesPoint>,
)

/** One sampled machine approval for human spot-checking. */
data class OpsAuditItem(
    val outcomeId: String,
    val contentId: String,
    val contentVersion: Int,
    val state: String,
    val autoApproved: Boolean,
    val auditSample: Boolean,
    val confidenceScore: Double?,
    val decidedAt: Long?,
    val title: String?,
    val subject: String?,
    val grade: String?,
    val taskType: String?,
    val reviewState: String?,
)

/** Response of GET /admin/ops/audit. */
data class OpsAuditPayload(
    val limit: Int,
    val items: List<OpsAuditItem>,
)
