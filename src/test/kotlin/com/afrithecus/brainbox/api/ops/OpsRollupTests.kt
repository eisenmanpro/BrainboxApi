package com.afrithecus.brainbox.api.ops

import com.afrithecus.brainbox.api.content.ContentMetrics
import com.afrithecus.brainbox.api.content.ContentPricing
import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import com.afrithecus.brainbox.api.content.entity.ModerationOutcomeEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.repository.ModerationOutcomeRepository
import com.afrithecus.brainbox.api.ops.repository.OpsMetricRollupRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

/**
 * O1 hourly rollup: the previous facts become one idempotent row per
 * (bucket, metric, dimension); the retention purge removes only rows past the
 * window. The scheduler is disabled in the test profile, so the service is driven
 * directly with an explicit bucket.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@ActiveProfiles("test")
@Transactional
class OpsRollupTests(
    @Autowired private val rollup: OpsRollupService,
    @Autowired private val rollups: OpsMetricRollupRepository,
    @Autowired private val outcomes: ModerationOutcomeRepository,
    @Autowired private val jobs: GenerationJobRepository,
    @Autowired private val modelCalls: ModelCallRepository,
    @Autowired private val agentRuns: AgentRunRepository,
    @Autowired private val metrics: ContentMetrics,
    @Autowired private val clock: Clock,
) {

    @Test
    fun `the rollup writes the previous facts and recomputing an hour does not duplicate`() {
        val bucket = clock.instant().truncatedTo(ChronoUnit.HOURS)
        val provider = "test-provider-" + UUID.randomUUID().toString().replace("-", "").take(8)

        outcomes.save(
            ModerationOutcomeEntity().apply {
                contentType = "UNIT"
                contentId = UUID.randomUUID()
                contentVersion = 1
                state = "REVIEWED"
                autoApproved = true
                decidedAt = clock.instant()
            }
        )
        jobs.save(
            GenerationJobEntity().apply {
                generationKey = "test:rollup:" + UUID.randomUUID()
                taskType = "NOTES"
                gradeLevel = "Grade 4"
                status = "FAILED"
                source = "BATCH"
            }
        )
        seedCall(provider, success = true, prompt = 1000, completion = 500, latencyMs = 100, error = null)
        seedCall(provider, success = false, prompt = 0, completion = 0, latencyMs = 300, error = "request timed out")

        rollup.rollupHour(bucket)

        check(value(bucket, OpsMetrics.PUBLISHED_UNITS, "") >= 1.0)
        check(value(bucket, OpsMetrics.AUTO_APPROVED, "") >= 1.0)
        check(value(bucket, OpsMetrics.JOBS_FAILED, "BATCH") >= 1.0)
        check(abs(value(bucket, OpsMetrics.GENERATION_LATENCY_MS, provider) - 200.0) < 1e-9)
        check(value(bucket, OpsMetrics.TOKENS_PROMPT, provider) == 1000.0)
        check(value(bucket, OpsMetrics.TOKENS_COMPLETION, provider) == 500.0)
        check(value(bucket, OpsMetrics.COST_MICROS, provider) == ContentPricing.costMicros(1000, 500).toDouble())
        check(value(bucket, OpsMetrics.PROVIDER_ERRORS, OpsMetrics.providerErrorDimension(provider, "timeout")) == 1.0)

        // Recomputing the same hour replaces the rows, never duplicates them.
        val before = rowsForBucket(bucket)
        val firstPublished = value(bucket, OpsMetrics.PUBLISHED_UNITS, "")
        rollup.rollupHour(bucket)
        check(rowsForBucket(bucket) == before) { "recomputing an hour changed the row count" }
        check(value(bucket, OpsMetrics.PUBLISHED_UNITS, "") == firstPublished)
    }

    @Test
    fun `the retention purge removes only rows older than the window`() {
        val now = clock.instant()
        val old = rollups.save(rollupRow(now.minus(Duration.ofDays(100)), "test.retention.old"))
        val recent = rollups.save(rollupRow(now.minus(Duration.ofDays(10)), "test.retention.recent"))

        val removed = rollup.purgeOldRollups(now.minus(Duration.ofDays(90)))

        check(removed >= 1L)
        check(rollups.findById(old.id).isEmpty) { "a 100-day-old rollup must be purged" }
        check(rollups.findById(recent.id).isPresent) { "a 10-day-old rollup must be kept" }
    }

    @Test
    fun `auto-approval exception counters become a per-hour delta from the stored snapshot`() {
        val bucket = clock.instant().truncatedTo(ChronoUnit.HOURS)
        val previous = bucket.minus(1, ChronoUnit.HOURS)
        val reason = "AUDIT_" + UUID.randomUUID().toString().replace("-", "").take(8)

        // Two exceptions before the baseline snapshot, one after: the delta is one.
        metrics.recordAutoApprovalException(reason)
        metrics.recordAutoApprovalException(reason)
        rollup.rollupHour(previous)
        check(value(previous, OpsMetrics.AUTOAPPROVE_EXCEPTIONS, reason) == 0.0) {
            "the first recorded hour has no baseline, so the delta is zero"
        }

        metrics.recordAutoApprovalException(reason)
        rollup.rollupHour(bucket)
        check(value(bucket, OpsMetrics.AUTOAPPROVE_EXCEPTIONS, reason) == 1.0) {
            "expected one new exception in the bucket"
        }
    }

    // ---------------------------------------------------------------- fixtures

    private fun seedCall(
        provider: String,
        success: Boolean,
        prompt: Int,
        completion: Int,
        latencyMs: Long,
        error: String?,
    ) {
        val run = agentRuns.save(
            AgentRunEntity().apply {
                generationKey = "test:rollup:run:" + UUID.randomUUID()
                status = "SUCCEEDED"
            }
        )
        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                this.provider = provider
                promptTokens = prompt
                completionTokens = completion
                costMicros = if (success) ContentPricing.costMicros(prompt, completion) else 0L
                this.latencyMs = latencyMs
                this.success = success
                this.error = error
            }
        )
    }

    private fun rollupRow(bucketStart: java.time.Instant, metric: String) =
        com.afrithecus.brainbox.api.ops.entity.OpsMetricRollupEntity().apply {
            this.bucketStart = bucketStart
            this.metric = metric
            this.dimension = ""
            this.value = 1.0
        }

    private fun value(bucket: java.time.Instant, metric: String, dimension: String): Double =
        rollups.findByBucketStartAndMetricAndDimension(bucket, metric, dimension)?.value
            ?: error("missing rollup " + metric + "/" + dimension + " at " + bucket)

    private fun rowsForBucket(bucket: java.time.Instant): Int =
        rollups.findAll().count { it.bucketStart == bucket }
}
