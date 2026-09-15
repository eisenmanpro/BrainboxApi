package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * H1 pipeline metrics contract. A [SimpleMeterRegistry] keeps this a fast unit
 * test: it proves the recording API emits the documented names and tags, and the
 * provider-error classifier maps failures to the coarse reason vocabulary.
 */
class ContentMetricsTests {

    private val registry = SimpleMeterRegistry()
    private val metrics = ContentMetrics(registry)

    @Test
    fun `generation latency is a timed provider-tagged sample`() {
        metrics.recordGenerationLatency("deepseek", 5_000_000L)
        metrics.recordGenerationLatency("deepseek", 7_000_000L)

        val timer = registry.find(ContentMetrics.METRIC_GENERATION_LATENCY).tag("provider", "deepseek").timer()
        check(timer != null)
        check(timer.count() == 2L)
        check(timer.totalTime(TimeUnit.MILLISECONDS) >= 11.9)
        check(timer.totalTime(TimeUnit.MILLISECONDS) <= 12.1)
    }

    @Test
    fun `provider errors count once per call by provider and reason`() {
        metrics.recordProviderError("deepseek", ContentMetrics.REASON_TIMEOUT)
        metrics.recordProviderError("deepseek", ContentMetrics.REASON_TIMEOUT)
        metrics.recordProviderError("deepseek", ContentMetrics.REASON_STATUS)

        check(
            registry.counter(
                ContentMetrics.METRIC_PROVIDER_ERRORS,
                "provider", "deepseek",
                "reason", ContentMetrics.REASON_TIMEOUT,
            ).count() == 2.0,
        )
        check(
            registry.counter(
                ContentMetrics.METRIC_PROVIDER_ERRORS,
                "provider", "deepseek",
                "reason", ContentMetrics.REASON_STATUS,
            ).count() == 1.0,
        )
    }

    @Test
    fun `token counter separates prompt and completion`() {
        metrics.recordTokens(1000, 500)
        metrics.recordTokens(200, 0)

        check(registry.counter(ContentMetrics.METRIC_TOKENS, "type", "prompt").count() == 1200.0)
        check(registry.counter(ContentMetrics.METRIC_TOKENS, "type", "completion").count() == 500.0)
    }

    @Test
    fun `auto-approval counter splits approved from exception and keeps the reason`() {
        metrics.recordAutoApproved()
        metrics.recordAutoApproved()
        metrics.recordAutoApprovalException("STRUCTURE_NO_QUESTIONS")
        metrics.recordAutoApprovalException("STRUCTURE_NO_QUESTIONS")

        check(registry.counter(ContentMetrics.METRIC_AUTOAPPROVE, "result", "approved").count() == 2.0)
        check(
            registry.counter(
                ContentMetrics.METRIC_AUTOAPPROVE,
                "result", "exception",
                "reason", "STRUCTURE_NO_QUESTIONS",
            ).count() == 2.0,
        )
    }

    @Test
    fun `provider failure reasons are coarse and stable`() {
        check(ProviderErrorReasons.reasonFor(HttpTimeoutException("slow")) == ContentMetrics.REASON_TIMEOUT)
        check(ProviderErrorReasons.reasonFor(TimeoutException("slow")) == ContentMetrics.REASON_TIMEOUT)
        check(
            ProviderErrorReasons.reasonFor(ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek request failed: request timed out")) ==
                ContentMetrics.REASON_TIMEOUT,
        )
        check(
            ProviderErrorReasons.reasonFor(ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek returned malformed JSON: bad token")) ==
                ContentMetrics.REASON_PARSE,
        )
        check(
            ProviderErrorReasons.reasonFor(ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "DeepSeek returned 503")) ==
                ContentMetrics.REASON_STATUS,
        )
        check(ProviderErrorReasons.reasonFor(IllegalStateException("model exploded")) == ContentMetrics.REASON_OTHER)
    }
}
