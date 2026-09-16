package com.afrithecus.brainbox.api.content

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * H1 pipeline metrics. These make the numbers we previously measured by hand in
 * tests (queue depth, generation latency, provider errors, token spend and the
 * auto-approval rate) real actuator meters. Only the existing [MeterRegistry] bean
 * is used; no registry dependency was added.
 *
 * Names and tags are part of the contract and must stay stable:
 *
 *  - `brainbox.content.generation.latency` timer, tag `provider`: wall time of a
 *    single provider `generate` call, success or failure.
 *  - `brainbox.content.provider.errors` counter, tags `provider`, `reason`
 *    (`timeout` | `parse` | `status` | `other`): one per failed provider call.
 *  - `brainbox.content.tokens` counter, tag `type` (`prompt` | `completion`):
 *    tokens consumed by a successful generation.
 *  - `brainbox.content.autoapprove` counter, tag `result` (`approved` |
 *    `exception`), plus `reason` on exceptions: the first blocker/warning code
 *    from the validator report (or the failing gate when validation never ran).
 *    The auto-approval rate is `result=approved` divided by the sum of both.
 *
 * Queue depth (`brainbox.content.queue.depth`) lives in [ContentQueueMetrics]
 * because it reads the generation_jobs table.
 */
@Component
class ContentMetrics(private val registry: MeterRegistry) {

    /** Records the duration of one provider generation call, in nanoseconds. */
    fun recordGenerationLatency(provider: String, nanos: Long) {
        registry.timer(METRIC_GENERATION_LATENCY, TAG_PROVIDER, provider)
            .record(nanos, TimeUnit.NANOSECONDS)
    }

    /** Records one failed provider call with its coarse reason. */
    fun recordProviderError(provider: String, reason: String) {
        registry.counter(METRIC_PROVIDER_ERRORS, TAG_PROVIDER, provider, TAG_REASON, reason).increment()
    }

    /** Records the prompt and completion tokens of a successful generation. */
    fun recordTokens(promptTokens: Int, completionTokens: Int) {
        if (promptTokens > 0) {
            registry.counter(METRIC_TOKENS, TAG_TYPE, TYPE_PROMPT).increment(promptTokens.toDouble())
        }
        if (completionTokens > 0) {
            registry.counter(METRIC_TOKENS, TAG_TYPE, TYPE_COMPLETION).increment(completionTokens.toDouble())
        }
    }

    /** Records a machine approval; the successful half of the auto-approval rate. */
    fun recordAutoApproved() {
        registry.counter(METRIC_AUTOAPPROVE, TAG_RESULT, RESULT_APPROVED).increment()
    }

    /** Records a gate exception with the first blocker/warning code that tripped it. */
    fun recordAutoApprovalException(reason: String) {
        registry.counter(METRIC_AUTOAPPROVE, TAG_RESULT, RESULT_EXCEPTION, TAG_REASON, reason).increment()
    }

    companion object {
        const val METRIC_GENERATION_LATENCY = "brainbox.content.generation.latency"
        const val METRIC_PROVIDER_ERRORS = "brainbox.content.provider.errors"
        const val METRIC_TOKENS = "brainbox.content.tokens"
        const val METRIC_AUTOAPPROVE = "brainbox.content.autoapprove"

        const val TAG_PROVIDER = "provider"
        const val TAG_REASON = "reason"
        const val TAG_TYPE = "type"
        const val TAG_RESULT = "result"

        const val TYPE_PROMPT = "prompt"
        const val TYPE_COMPLETION = "completion"

        const val RESULT_APPROVED = "approved"
        const val RESULT_EXCEPTION = "exception"

        const val REASON_TIMEOUT = "timeout"
        const val REASON_PARSE = "parse"
        const val REASON_STATUS = "status"
        const val REASON_OTHER = "other"
    }
}

/**
 * Maps a failed provider call to the coarse `reason` tag. The provider wraps
 * transport, HTTP-status and JSON-parse failures in one exception type, so the
 * classifier is deliberately message-based and falls back to `other`.
 */
object ProviderErrorReasons {

    fun reasonFor(failure: Throwable): String =
        reasonForMessage(failure.message, failure is HttpTimeoutException || failure is TimeoutException)

    /**
     * The same coarse classifier over a stored `model_calls.error` message, so the
     * O1 rollup reuses the exact vocabulary the live counter records. [isTimeoutType]
     * lets the caller preserve the exception-type signal when only a message is
     * available it is false for a persisted string.
     */
    fun reasonForMessage(message: String?, isTimeoutType: Boolean = false): String {
        val text = message.orEmpty().lowercase()
        return when {
            isTimeoutType ||
                text.contains("timed out") ||
                text.contains("timeout") -> ContentMetrics.REASON_TIMEOUT

            text.contains("malformed") || text.contains("parse") -> ContentMetrics.REASON_PARSE

            text.contains("returned") || text.contains("status") -> ContentMetrics.REASON_STATUS

            else -> ContentMetrics.REASON_OTHER
        }
    }
}
