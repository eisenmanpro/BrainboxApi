package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.state.RateLimitStore
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key (identifier) auth throttling with exponential backoff
 * (docs/ongoing/api_auth_changes.md AU-P1.2). The IP token bucket in
 * RateLimitFilter remains the coarse control; this locks a single identifier
 * after repeated failures and reports a Retry-After hint.
 *
 * The counters live in a [RateLimitStore]. With `app.redis.enabled=true` that store is
 * Redis, so two API nodes count the same failures and an attacker cannot multiply their
 * attempts by getting routed to a different node. With the default in-process store the
 * behaviour is exactly what it was before any shared state existed, and an unavailable
 * Redis degrades to that same in-process counting (the store's policy is ALLOW, because
 * locking every user out of the product is worse than briefly losing cross-node counting).
 */
@Component
class AuthThrottle(
    private val clock: Clock,
    private val rateLimits: RateLimitStore,
) {

    private class State {
        var failures: Int = 0
        var windowStart: Instant? = null
        var lockedUntil: Instant? = null
    }

    /** Fallback used when the shared store cannot answer. */
    private val states = ConcurrentHashMap<String, State>()

    fun check(key: String) {
        val now = clock.instant()
        val sharedLock = rateLimits.get(lockKey(key))
        if (sharedLock != null && sharedLock > now.toEpochMilli()) {
            throw tooManyAttempts(Duration.ofMillis(sharedLock - now.toEpochMilli()))
        }
        val state = states[key] ?: return
        val lockedUntil = state.lockedUntil ?: return
        if (lockedUntil.isAfter(now)) throw tooManyAttempts(Duration.between(now, lockedUntil))
    }

    fun recordFailure(key: String) {
        val now = clock.instant()
        val state = states.computeIfAbsent(key) { State() }
        val windowStart = state.windowStart
        if (windowStart == null || Duration.between(windowStart, now).seconds > WINDOW_SECONDS) {
            state.windowStart = now
            state.failures = 0
            state.lockedUntil = null
        }
        state.failures += 1

        // The shared counter is the authoritative one when a shared store is configured:
        // it is what makes the limit hold across nodes. A null means the store failed and
        // the deployment allows the attempt, so the local count decides.
        val effective = rateLimits.increment(failureKey(key), Duration.ofSeconds(WINDOW_SECONDS))
            ?.toInt()
            ?: state.failures
        if (effective >= MAX_FAILURES) {
            val over = (effective - MAX_FAILURES).coerceAtMost(MAX_BACKOFF_STEPS)
            val backoff = (BASE_LOCK_SECONDS shl over).coerceAtMost(MAX_LOCK_SECONDS)
            // The lock is shared too, with a TTL that expires it on its own.
            rateLimits.put(lockKey(key), now.plusSeconds(backoff).toEpochMilli(), Duration.ofSeconds(backoff))
            state.lockedUntil = now.plusSeconds(backoff)
        }
    }

    fun clear(key: String) {
        states.remove(key)
        rateLimits.clear(failureKey(key))
        rateLimits.clear(lockKey(key))
    }

    private fun tooManyAttempts(remaining: Duration): ApiException {
        val seconds = remaining.seconds.coerceAtLeast(1)
        return ApiException(
            ApiErrorCode.TOO_MANY_REQUESTS,
            "Too many attempts. Try again in " + seconds + " seconds.",
            mapOf("retryAfter" to seconds),
        )
    }

    private fun failureKey(key: String) = "auth-fail:" + key

    private fun lockKey(key: String) = "auth-lock:" + key

    private companion object {
        const val MAX_FAILURES = 5
        const val WINDOW_SECONDS = 900L
        const val BASE_LOCK_SECONDS = 30L
        const val MAX_LOCK_SECONDS = 900L
        const val MAX_BACKOFF_STEPS = 4
    }
}
