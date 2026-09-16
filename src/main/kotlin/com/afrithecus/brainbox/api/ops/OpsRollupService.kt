package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.content.ContentMetrics
import com.afrithecus.brainbox.api.content.ProviderErrorReasons
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.ops.entity.OpsMetricRollupEntity
import com.afrithecus.brainbox.api.ops.repository.OpsMetricRollupRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * O1 hourly rollup. Aggregates the facts the content pipeline already stores into
 * `ops_metric_rollup` for the previous complete hour: published and auto-approved
 * units, failed jobs by source, provider latency/errors/tokens/cost from
 * `model_calls`, and a shelf-coverage snapshot. Auto-approval gate exceptions only
 * exist as the live Micrometer counter, so they are snapshotted hourly and stored
 * as a per-hour delta; the cumulative snapshot is kept under an internal metric so
 * the next run can subtract it. No external observability dependency.
 *
 * Idempotent per (bucket_start, metric, dimension): each upsert replaces the one
 * row for that key, so recomputing an hour never duplicates.
 */
@Service
class OpsRollupService(
    private val rollups: OpsMetricRollupRepository,
    private val outcomes: ModerationOutcomeRepository,
    private val jobs: GenerationJobRepository,
    private val modelCalls: ModelCallRepository,
    private val coverage: OpsCoverageService,
    private val registry: MeterRegistry,
    private val properties: AppOpsProperties,
    private val clock: Clock,
) {

    /** The scheduled entry point: rolls up the previous complete hour. */
    @Transactional
    fun rollupPreviousHour(): Int = rollupHour(previousHourStart(clock.instant()))

    /**
     * Rolls [bucketStart] forward one hour. Exposed so the scheduler and tests can
     * target an exact bucket; every value is recomputed from the stored facts.
     */
    @Transactional
    fun rollupHour(bucketStart: Instant): Int {
        val from = bucketStart
        val to = bucketStart.plus(1, ChronoUnit.HOURS)
        var written = 0

        written += upsert(
            bucketStart, OpsMetrics.PUBLISHED_UNITS, "",
            outcomes.countByStateAndDecidedAtGreaterThanEqualAndDecidedAtLessThan(STATE_REVIEWED, from, to).toDouble(),
        )
        written += upsert(
            bucketStart, OpsMetrics.AUTO_APPROVED, "",
            outcomes.countByAutoApprovedTrueAndDecidedAtGreaterThanEqualAndDecidedAtLessThan(from, to).toDouble(),
        )

        jobs.countFailedBySourceInWindow(from, to).forEach { row ->
            written += upsert(bucketStart, OpsMetrics.JOBS_FAILED, row.source, row.total.toDouble())
        }

        modelCalls.aggregateByProviderInWindow(from, to).forEach { row ->
            val averageLatency =
                if (row.callCount > 0L) row.latencySum.toDouble() / row.callCount.toDouble() else 0.0
            written += upsert(bucketStart, OpsMetrics.GENERATION_LATENCY_MS, row.provider, averageLatency)
            written += upsert(bucketStart, OpsMetrics.TOKENS_PROMPT, row.provider, row.promptTokens.toDouble())
            written += upsert(bucketStart, OpsMetrics.TOKENS_COMPLETION, row.provider, row.completionTokens.toDouble())
            written += upsert(bucketStart, OpsMetrics.COST_MICROS, row.provider, row.costMicros.toDouble())
        }

        val errorsByDimension = LinkedHashMap<String, Long>()
        modelCalls.findFailuresInWindow(from, to).forEach { row ->
            val reason = ProviderErrorReasons.reasonForMessage(row.error)
            val dimension = OpsMetrics.providerErrorDimension(row.provider, reason)
            errorsByDimension[dimension] = (errorsByDimension[dimension] ?: 0L) + 1L
        }
        errorsByDimension.forEach { (dimension, count) ->
            written += upsert(bucketStart, OpsMetrics.PROVIDER_ERRORS, dimension, count.toDouble())
        }

        val shelf = coverage.coverage()
        written += upsert(bucketStart, OpsMetrics.COVERAGE_TOTAL_TOPICS, "", shelf.overall.totalTopics.toDouble())
        written += upsert(bucketStart, OpsMetrics.COVERAGE_PUBLISHED_TOPICS, "", shelf.overall.coveredTopics.toDouble())
        written += upsert(
            bucketStart, OpsMetrics.COVERAGE_PRACTICE_PAPERS, "",
            shelf.rows.sumOf { it.practicePapersPublished }.toDouble(),
        )
        written += upsert(
            bucketStart, OpsMetrics.COVERAGE_STUDY_GUIDES, "",
            shelf.rows.sumOf { it.studyGuidesPublished }.toDouble(),
        )

        written += snapshotAutoApprovalExceptions(bucketStart)
        return written
    }

    /** Retention: purge rollups past the configured window (default 90 days). */
    @Transactional
    fun purgeOldRollups(): Long =
        rollups.deleteByBucketStartBefore(clock.instant().minus(Duration.ofDays(properties.rollup.retentionDays)))

    /** Retention with an explicit cutoff, for tests and manual maintenance. */
    @Transactional
    fun purgeOldRollups(cutoff: Instant): Long = rollups.deleteByBucketStartBefore(cutoff)

    /**
     * Reads the live Micrometer auto-approval exception counters and stores both
     * the cumulative snapshot and the per-hour delta against the previous bucket.
     * With no previous snapshot the delta is 0 rather than the whole process
     * lifetime, so the first recorded hour never over-counts.
     */
    private fun snapshotAutoApprovalExceptions(bucketStart: Instant): Int {
        val previous = bucketStart.minus(1, ChronoUnit.HOURS)
        val byReason = LinkedHashMap<String, Double>()
        registry.find(ContentMetrics.METRIC_AUTOAPPROVE)
            .tag(ContentMetrics.TAG_RESULT, ContentMetrics.RESULT_EXCEPTION)
            .counters()
            .forEach { counter ->
                val reason = counter.id.getTag(ContentMetrics.TAG_REASON) ?: "unknown"
                byReason[reason] = (byReason[reason] ?: 0.0) + counter.count()
            }

        var written = 0
        byReason.forEach { (reason, current) ->
            val previousValue = rollups
                .findByBucketStartAndMetricAndDimension(previous, OpsMetrics.AUTOAPPROVE_SNAPSHOT, reason)
                ?.value
            val delta = if (previousValue == null) 0.0 else (current - previousValue).coerceAtLeast(0.0)
            written += upsert(bucketStart, OpsMetrics.AUTOAPPROVE_SNAPSHOT, reason, current)
            written += upsert(bucketStart, OpsMetrics.AUTOAPPROVE_EXCEPTIONS, reason, delta)
        }
        return written
    }

    /** One idempotent upsert keyed by (bucket_start, metric, dimension). */
    private fun upsert(bucketStart: Instant, metric: String, dimension: String, value: Double): Int {
        val entity = rollups.findByBucketStartAndMetricAndDimension(bucketStart, metric, dimension)
            ?: OpsMetricRollupEntity().apply {
                this.bucketStart = bucketStart
                this.metric = metric
                this.dimension = dimension
            }
        entity.value = value
        rollups.save(entity)
        return 1
    }

    private companion object {
        const val STATE_REVIEWED = "REVIEWED"
    }

    /** The start of the hour before [now], which is complete by definition. */
    internal fun previousHourStart(now: Instant): Instant =
        now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS)
}
