package com.afrithecus.brainbox.api.content.ai

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-node provider health for the routing pool: consecutive failures (a
 * threshold opens a short cooldown that demotes the provider to the back) and an
 * EWMA of observed latency (the FASTEST policy and the CHEAPEST tie-break).
 *
 * It is deliberately in-memory: the durable per-call record is `model_calls` and
 * the rolled-up history is the O1 ops API. A restart resets the breaker, which is
 * safe because the routing provider always keeps a demoted candidate in the pool
 * and the worker retries with backoff; this is not a distributed circuit breaker
 * (that is the Redis phase).
 */
@Component
class ProviderHealth(
    private val properties: AppAiProperties,
    private val clock: Clock,
) {

    private data class State(
        var consecutiveFailures: Int = 0,
        var openUntil: Instant? = null,
        var ewmaLatencyMs: Long? = null,
    )

    private val states = ConcurrentHashMap<String, State>()

    /** True while [name] is serving out its cooldown after repeated failures. */
    fun isDemoted(name: String): Boolean {
        val openUntil = states[name]?.openUntil ?: return false
        return clock.instant().isBefore(openUntil)
    }

    /** The EWMA latency of successful calls, or null before the first success. */
    fun observedLatencyMs(name: String): Long? = states[name]?.ewmaLatencyMs

    @Synchronized
    fun recordSuccess(name: String, latencyMs: Long) {
        val state = states.computeIfAbsent(name) { State() }
        state.consecutiveFailures = 0
        state.openUntil = null
        val previous = state.ewmaLatencyMs
        state.ewmaLatencyMs = if (previous == null) latencyMs else PREVIOUS_WEIGHT * previous / TOTAL_WEIGHT + latencyMs / TOTAL_WEIGHT
    }

    @Synchronized
    fun recordFailure(name: String) {
        val state = states.computeIfAbsent(name) { State() }
        state.consecutiveFailures += 1
        if (state.consecutiveFailures >= properties.routing.failureThreshold.coerceAtLeast(1)) {
            state.openUntil = clock.instant().plus(Duration.ofSeconds(properties.routing.cooldownSeconds.coerceAtLeast(0L)))
        }
    }

    private companion object {
        /** EWMA weighting: the newest call is 1/5 of the average. */
        const val PREVIOUS_WEIGHT = 4L
        const val TOTAL_WEIGHT = 5L
    }
}
