package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.content.ProviderErrorReasons
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.ops.entity.OpsMetricRollupEntity
import com.afrithecus.brainbox.api.ops.repository.OpsMetricRollupRepository
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
 * `model_calls`, a shelf-coverage snapshot, and the auto-approval exception mix.
 *
 * The exception mix is a distribution snapshot read from the durable
 * `content_units.auto_approve_blocked_reason`: the number of still-UNREVIEWED units
 * per reason. It is database-derived and restart-safe - there is no in-process
 * Micrometer counter and no per-hour delta to reconstruct, so the reason survives a
 * restart and the first hour of a process is not zero. Recomputing an hour deletes
 * and rewrites the exception rows for that bucket, so a reason that disappears is
 * never left stale.
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
    private val units: ContentUnitRepository,
    private val coverage: OpsCoverageService,
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

        written += rollupAutoApprovalExceptions(bucketStart)
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
     * Writes the exception mix for [bucketStart] from the persisted facts: the count
     * of still-UNREVIEWED units per `auto_approve_blocked_reason`. This is a
     * distribution snapshot, so it is recomputed in full each run and does not depend
     * on any in-process counter; the previous rows for the bucket are replaced so a
     * reason that no longer appears is not left stale.
     */
    private fun rollupAutoApprovalExceptions(bucketStart: Instant): Int {
        rollups.deleteByBucketStartAndMetric(bucketStart, OpsMetrics.AUTOAPPROVE_EXCEPTIONS)
        var written = 0
        units.countUnreviewedByAutoApproveBlockedReason().forEach { row ->
            written += upsert(bucketStart, OpsMetrics.AUTOAPPROVE_EXCEPTIONS, row.reason, row.total.toDouble())
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
