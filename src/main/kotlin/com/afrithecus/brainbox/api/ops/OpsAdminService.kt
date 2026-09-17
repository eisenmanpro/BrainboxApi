package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.ContentQueueAdminService
import com.afrithecus.brainbox.api.content.GenerationBudgetService
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.entity.ToolCallEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.content.repository.ToolCallRepository
import com.afrithecus.brainbox.api.ops.repository.OpsMetricRollupRepository
import com.afrithecus.brainbox.api.ops.web.OpsAgentRunTrace
import com.afrithecus.brainbox.api.ops.web.OpsAuditItem
import com.afrithecus.brainbox.api.ops.web.OpsAuditPayload
import com.afrithecus.brainbox.api.ops.web.OpsJobSummary
import com.afrithecus.brainbox.api.ops.web.OpsJobTrace
import com.afrithecus.brainbox.api.ops.web.OpsModelCallTrace
import com.afrithecus.brainbox.api.ops.web.OpsModerationSummary
import com.afrithecus.brainbox.api.ops.web.OpsSummary
import com.afrithecus.brainbox.api.ops.web.OpsTimeseriesPayload
import com.afrithecus.brainbox.api.ops.web.OpsTimeseriesPoint
import com.afrithecus.brainbox.api.ops.web.OpsToolCallTrace
import com.afrithecus.brainbox.api.ops.web.OpsUnitSummary
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * O1 admin ops read model. This is the contract the future internal operations
 * frontend consumes: a live summary, live coverage, the stored rollup history as a
 * bounded time series, and the sampled machine-approval audit. Every query is an
 * aggregate over a coarse dimension; there is no per-topic cardinality here.
 */
@Service
class OpsAdminService(
    private val rollups: OpsMetricRollupRepository,
    private val outcomes: ModerationOutcomeRepository,
    private val jobs: GenerationJobRepository,
    private val modelCalls: ModelCallRepository,
    private val agentRuns: AgentRunRepository,
    private val toolCalls: ToolCallRepository,
    private val units: ContentUnitRepository,
    private val coverageService: OpsCoverageService,
    private val queueAdmin: ContentQueueAdminService,
    private val budgets: GenerationBudgetService,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun summary(): OpsSummary {
        val now = clock.instant()
        val dayStart = startOfUtcDay(now)
        val queue = queueAdmin.summary()
        val coverage = coverageService.coverage()

        val autoApprovedToday = outcomes
            .countByAutoApprovedTrueAndDecidedAtGreaterThanEqualAndDecidedAtLessThan(dayStart, now)
        val publishedUnitsToday = outcomes
            .countByStateAndDecidedAtGreaterThanEqualAndDecidedAtLessThan(STATE_REVIEWED, dayStart, now)
        val exceptionUnitsToday = units
            .countByReviewStateAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(STATE_UNREVIEWED, dayStart, now)
        val attempts = autoApprovedToday + exceptionUnitsToday

        val providers = modelCalls.aggregateByProviderInWindow(dayStart, now)
        val calls = providers.sumOf { it.callCount }
        val latencySum = providers.sumOf { it.latencySum }
        val providerErrors = modelCalls.findFailuresInWindow(dayStart, now).size.toLong()
        val promptTokens = providers.sumOf { it.promptTokens }
        val completionTokens = providers.sumOf { it.completionTokens }
        val costMicros = providers.sumOf { it.costMicros }

        val budgetLimit = budgets.platformBudget()
        val budgetUsed = budgets.platformUsedToday()
        val oldestQueued = jobs.oldestQueuedAt()

        val latestExceptionBucket = rollups
            .findFirstByMetricOrderByBucketStartDesc(OpsMetrics.AUTOAPPROVE_EXCEPTIONS)
            ?.bucketStart
        val reasonMix = latestExceptionBucket?.let { bucket ->
            rollups.findAllByMetricAndBucketStartOrderByDimensionAsc(OpsMetrics.AUTOAPPROVE_EXCEPTIONS, bucket)
                .associate { it.dimension to it.value.toLong() }
        }.orEmpty()

        return OpsSummary(
            generatedAt = now.toEpochMilli(),
            coverage = coverage.overall,
            coverageBySubjectGrade = coverage.rows,
            queueDepth = queue.depth,
            queueDepthBySource = queue.bySource,
            oldestQueuedAgeSeconds = oldestQueued?.let { Duration.between(it, now).seconds.coerceAtLeast(0L) },
            autoApprovedToday = autoApprovedToday,
            exceptionUnitsToday = exceptionUnitsToday,
            autoApprovalRate = if (attempts > 0L) autoApprovedToday.toDouble() / attempts.toDouble() else null,
            autoApprovalReasonMix = reasonMix,
            generationBudgetLimit = budgetLimit,
            generationBudgetUsed = budgetUsed,
            generationBudgetRemaining = if (budgetLimit <= 0) null else (budgetLimit - budgetUsed).coerceAtLeast(0L).toInt(),
            providerCallsToday = calls,
            providerErrorsToday = providerErrors,
            providerErrorRate = if (calls > 0L) providerErrors.toDouble() / calls.toDouble() else null,
            averageGenerationLatencyMs = if (calls > 0L) latencySum.toDouble() / calls.toDouble() else null,
            promptTokensToday = promptTokens,
            completionTokensToday = completionTokens,
            costMicrosToday = costMicros,
            publishedUnitsToday = publishedUnitsToday,
            costPerPublishedItemMicros = if (publishedUnitsToday > 0L) {
                costMicros.toDouble() / publishedUnitsToday.toDouble()
            } else {
                null
            },
            latestRollupBucket = rollups.findFirstByOrderByBucketStartDesc()?.bucketStart?.toEpochMilli(),
        )
    }

    @Transactional(readOnly = true)
    fun coverage() = coverageService.coverage()

    @Transactional(readOnly = true)
    fun timeseries(metric: String?, dimension: String?, from: Long?, to: Long?): OpsTimeseriesPayload {
        val normalizedMetric = metric?.trim().orEmpty()
        if (normalizedMetric.isEmpty()) throw invalidArgument("metric is required")

        val now = clock.instant()
        val end = to?.let { Instant.ofEpochMilli(it) } ?: now
        val start = from?.let { Instant.ofEpochMilli(it) } ?: end.minus(Duration.ofDays(DEFAULT_WINDOW_DAYS))
        if (!start.isBefore(end)) throw invalidArgument("from must be before to")

        val normalizedDimension = dimension?.trim()?.takeIf { it.isNotEmpty() }
        val rows = rollups.findSeries(
            normalizedMetric,
            normalizedDimension,
            start,
            end,
            PageRequest.of(0, MAX_POINTS + 1),
        )
        val truncated = rows.size > MAX_POINTS
        return OpsTimeseriesPayload(
            metric = normalizedMetric,
            dimension = normalizedDimension,
            from = start.toEpochMilli(),
            to = end.toEpochMilli(),
            limit = MAX_POINTS,
            truncated = truncated,
            points = rows.take(MAX_POINTS).map {
                OpsTimeseriesPoint(it.bucketStart.toEpochMilli(), it.metric, it.dimension, it.value)
            },
        )
    }

    @Transactional(readOnly = true)
    fun audit(limit: Int): OpsAuditPayload {
        val capped = limit.coerceIn(1, MAX_AUDIT)
        val items = outcomes
            .findByAutoApprovedTrueAndAuditSampleTrueOrderByDecidedAtDesc(PageRequest.of(0, capped))
            .map { outcome ->
                val unit = if (outcome.contentType == CONTENT_TYPE_UNIT) {
                    units.findById(outcome.contentId).orElse(null)
                } else {
                    null
                }
                OpsAuditItem(
                    outcomeId = outcome.id.toString(),
                    contentId = outcome.contentId.toString(),
                    contentVersion = outcome.contentVersion,
                    state = outcome.state,
                    autoApproved = outcome.autoApproved,
                    auditSample = outcome.auditSample,
                    confidenceScore = outcome.confidenceScore,
                    decidedAt = outcome.decidedAt?.toEpochMilli(),
                    title = unit?.title,
                    subject = unit?.subject,
                    grade = unit?.gradeLevel,
                    taskType = unit?.taskType,
                    reviewState = unit?.reviewState,
                )
            }
        return OpsAuditPayload(limit = capped, items = items)
    }

    /**
     * The full observable trace of one generation job: its durable row, every
     * agent run for the generation key (generation and the independent
     * verification), each run's provider calls and MCP tool calls, and the
     * projected unit with its moderation outcome. This is the per-job complement
     * to the aggregate summary/timeseries.
     */
    @Transactional(readOnly = true)
    fun jobTrace(jobIdRaw: String): OpsJobTrace {
        val jobId = runCatching { UUID.fromString(jobIdRaw) }.getOrNull()
            ?: throw invalidArgument("jobId is not a valid identifier")
        val job = jobs.findById(jobId).orElse(null) ?: throw notFound("Generation job not found")

        val runs = agentRuns.findAllByGenerationKeyOrderByCreatedAtDesc(job.generationKey)
            .sortedBy { it.createdAt }
            .map { run ->
                OpsAgentRunTrace(
                    runId = run.id.toString(),
                    promptVersion = run.promptVersion,
                    status = run.status,
                    model = run.model,
                    confidence = run.confidence,
                    iterations = run.iterations,
                    createdAt = run.createdAt.toEpochMilli(),
                    modelCalls = modelCalls.findAllByAgentRunId(run.id).sortedBy { it.createdAt }.map(::modelCallTrace),
                    toolCalls = toolCalls.findAllByAgentRunId(run.id).sortedBy { it.createdAt }.map(::toolCallTrace),
                )
            }

        val unit = units.findByGenerationKey(job.generationKey)
        val moderation = unit?.let {
            outcomes.findFirstByContentTypeAndContentIdOrderByCreatedAtDesc(CONTENT_TYPE_UNIT, it.id)
        }

        return OpsJobTrace(
            job = OpsJobSummary(
                jobId = job.id.toString(),
                generationKey = job.generationKey,
                taskType = job.taskType,
                conceptId = job.conceptId?.toString(),
                gradeLevel = job.gradeLevel,
                status = job.status,
                source = job.source,
                attempts = job.attempts,
                maxAttempts = job.maxAttempts,
                schoolId = job.schoolId?.toString(),
                runId = job.runId?.toString(),
                lastError = job.lastError,
                nextAttemptAt = job.nextAttemptAt?.toEpochMilli(),
                createdAt = job.createdAt.toEpochMilli(),
                updatedAt = job.updatedAt.toEpochMilli(),
            ),
            runs = runs,
            unit = unit?.let(::unitSummary),
            moderation = moderation?.let(::moderationSummary),
        )
    }

    private fun modelCallTrace(call: ModelCallEntity): OpsModelCallTrace = OpsModelCallTrace(
        callId = call.id.toString(),
        provider = call.provider,
        model = call.model,
        promptTokens = call.promptTokens,
        completionTokens = call.completionTokens,
        costMicros = call.costMicros,
        latencyMs = call.latencyMs,
        success = call.success,
        error = call.error,
        createdAt = call.createdAt.toEpochMilli(),
    )

    private fun toolCallTrace(call: ToolCallEntity): OpsToolCallTrace = OpsToolCallTrace(
        callId = call.id.toString(),
        toolName = call.toolName,
        success = call.success,
        latencyMs = call.latencyMs,
        createdAt = call.createdAt.toEpochMilli(),
    )

    private fun unitSummary(unit: ContentUnitEntity): OpsUnitSummary = OpsUnitSummary(
        unitId = unit.id.toString(),
        title = unit.title,
        subject = unit.subject,
        gradeLevel = unit.gradeLevel,
        taskType = unit.taskType,
        reviewState = unit.reviewState,
        status = unit.status,
        model = unit.model,
        tokens = unit.tokens,
        confidence = unit.confidence,
        answerKeyAgreement = unit.answerKeyAgreement,
        answerKeyVerifiedModel = unit.answerKeyVerifiedModel,
        answerKeyDropped = unit.answerKeyDropped,
        autoApproveBlockedReason = unit.autoApproveBlockedReason,
        contentSchemaVersion = unit.contentSchemaVersion,
        createdAt = unit.createdAt.toEpochMilli(),
    )

    private fun moderationSummary(outcome: ModerationOutcomeEntity): OpsModerationSummary = OpsModerationSummary(
        state = outcome.state,
        autoApproved = outcome.autoApproved,
        auditSample = outcome.auditSample,
        confidenceScore = outcome.confidenceScore,
        approvals = outcome.approvals,
        rejections = outcome.rejections,
        reviewerId = outcome.reviewerId?.toString(),
        decidedAt = outcome.decidedAt?.toEpochMilli(),
    )

    private fun startOfUtcDay(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    private companion object {
        const val STATE_REVIEWED = "REVIEWED"
        const val STATE_UNREVIEWED = "UNREVIEWED"
        const val CONTENT_TYPE_UNIT = "UNIT"
        const val MAX_AUDIT = 200
        const val MAX_POINTS = 1_000
        const val DEFAULT_WINDOW_DAYS = 7L
    }
}
