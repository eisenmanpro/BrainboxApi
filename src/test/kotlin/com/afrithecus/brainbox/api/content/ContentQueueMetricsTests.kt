package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * H1 queue-depth gauge against the real repository. The gauge is read on demand
 * (actuator scrape), so this asserts the value tracks uncommitted generation_jobs
 * rows in the same transaction and that every documented status tag is present.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentQueueMetricsTests(
    @Autowired private val registry: MeterRegistry,
    @Autowired private val generationJobs: GenerationJobRepository,
) {

    @Test
    fun `queue depth gauge tracks generation job counts by status`() {
        val queuedBefore = gauge("QUEUED")
        val failedBefore = gauge("FAILED")

        generationJobs.save(job("QUEUED"))
        generationJobs.save(job("QUEUED"))
        generationJobs.save(job("FAILED"))

        check(gauge("QUEUED") == queuedBefore + 2.0) { "QUEUED gauge did not move" }
        check(gauge("FAILED") == failedBefore + 1.0) { "FAILED gauge did not move" }
        check(gauge("RUNNING") >= 0.0)
        check(gauge("SUCCEEDED") >= 0.0)
    }

    @Test
    fun `queue depth exposes every documented status tag`() {
        ContentQueueMetrics.STATUSES.forEach { status ->
            check(registry.find(ContentQueueMetrics.METRIC_QUEUE_DEPTH).tag("status", status).gauge() != null) {
                "missing queue-depth gauge for " + status
            }
        }
    }

    @Test
    fun `budget used gauge is registered for the platform scope`() {
        val meter = requireNotNull(
            registry.find(ContentQueueMetrics.METRIC_BUDGET_USED)
                .tag(ContentQueueMetrics.TAG_SCOPE, ContentQueueMetrics.SCOPE_PLATFORM)
                .gauge()
        ) { "missing platform budget-used gauge" }
        val before = meter.value()
        generationJobs.save(job("QUEUED"))
        check(meter.value() == before + 1.0) { "budget-used gauge did not track a today job" }
    }

    private fun gauge(status: String): Double =
        requireNotNull(registry.find(ContentQueueMetrics.METRIC_QUEUE_DEPTH).tag("status", status).gauge()).value()

    private fun job(status: String) = GenerationJobEntity().apply {
        generationKey = "test:" + UUID.randomUUID()
        taskType = "NOTES"
        gradeLevel = "Grade 4"
        this.status = status
    }
}
