package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset

/**
 * H1 queue-depth gauge: `brainbox.content.queue.depth`, one time series per
 * `status` tag (`QUEUED`, `RUNNING`, `FAILED`, `SUCCEEDED`). The value is the
 * number of `generation_jobs` rows in that status, read from
 * [GenerationJobRepository] on scrape (actuator), so there is no polling loop and
 * no extra infrastructure. The gauge is a strong reference to this bean so it
 * cannot be collected while the registry lives.
 */
@Component
class ContentQueueMetrics(
    private val registry: MeterRegistry,
    private val generationJobs: GenerationJobRepository,
    private val clock: Clock,
) {

    @PostConstruct
    fun registerQueueDepthGauges() {
        STATUSES.forEach { status ->
            Gauge.builder(METRIC_QUEUE_DEPTH, this) { metrics: ContentQueueMetrics -> metrics.queueDepth(status) }
                .tag(TAG_STATUS, status)
                .description("generation_jobs rows currently in " + status)
                .strongReference(true)
                .register(registry)
        }
        Gauge.builder(METRIC_BUDGET_USED, this) { metrics: ContentQueueMetrics -> metrics.jobsToday() }
            .tag(TAG_SCOPE, SCOPE_PLATFORM)
            .description("generation_jobs rows created since the start of the current UTC day (H3 platform budget usage)")
            .strongReference(true)
            .register(registry)
    }

    /** One scrape sample; a database error must not break the whole scrape. */
    internal fun queueDepth(status: String): Double =
        runCatching { generationJobs.countByStatus(status).toDouble() }.getOrDefault(0.0)

    /** H3 budget gauge sample: jobs created since the start of the current UTC day. */
    internal fun jobsToday(): Double =
        runCatching { generationJobs.countByCreatedAtGreaterThanEqual(startOfUtcDay()).toDouble() }.getOrDefault(0.0)

    private fun startOfUtcDay(): java.time.Instant =
        clock.instant().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    companion object {
        const val METRIC_QUEUE_DEPTH = "brainbox.content.queue.depth"
        const val METRIC_BUDGET_USED = "brainbox.content.budget.used"
        const val TAG_STATUS = "status"
        const val TAG_SCOPE = "scope"
        const val SCOPE_PLATFORM = "platform"

        /** Every terminal and in-flight generation_jobs status. */
        val STATUSES: List<String> = listOf("QUEUED", "RUNNING", "FAILED", "SUCCEEDED")
    }
}
