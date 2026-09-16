package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.ContentQueueAdminService
import com.afrithecus.brainbox.api.content.GenerationBudgetService
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.ops.repository.OpsMetricRollupRepository
import com.afrithecus.brainbox.api.ops.web.OpsAuditItem
import com.afrithecus.brainbox.api.ops.web.OpsAuditPayload
import com.afrithecus.brainbox.api.ops.web.OpsSummary
import com.afrithecus.brainbox.api.ops.web.OpsTimeseriesPayload
import com.afrithecus.brainbox.api.ops.web.OpsTimeseriesPoint
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

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
