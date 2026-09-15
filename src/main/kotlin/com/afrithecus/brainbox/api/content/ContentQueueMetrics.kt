package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

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
    }

    /** One scrape sample; a database error must not break the whole scrape. */
    internal fun queueDepth(status: String): Double =
        runCatching { generationJobs.countByStatus(status).toDouble() }.getOrDefault(0.0)

    companion object {
        const val METRIC_QUEUE_DEPTH = "brainbox.content.queue.depth"
        const val TAG_STATUS = "status"

        /** Every terminal and in-flight generation_jobs status. */
        val STATUSES: List<String> = listOf("QUEUED", "RUNNING", "FAILED", "SUCCEEDED")
    }
}
