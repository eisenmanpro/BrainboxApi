package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
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
 */
@Component
class AuthThrottle(private val clock: Clock) {

    private class State {
        var failures: Int = 0
        var windowStart: Instant? = null
        var lockedUntil: Instant? = null
    }

    private val states = ConcurrentHashMap<String, State>()

    fun check(key: String) {
        val state = states[key] ?: return
        val lockedUntil = state.lockedUntil ?: return
        val now = clock.instant()
        if (lockedUntil.isAfter(now)) {
            val seconds = Duration.between(now, lockedUntil).seconds.coerceAtLeast(1)
            throw ApiException(
                ApiErrorCode.TOO_MANY_REQUESTS,
                "Too many attempts. Try again in " + seconds + " seconds.",
                mapOf("retryAfter" to seconds),
            )
        }
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
        if (state.failures >= MAX_FAILURES) {
            val over = (state.failures - MAX_FAILURES).coerceAtMost(MAX_BACKOFF_STEPS)
            val backoff = (BASE_LOCK_SECONDS shl over).coerceAtMost(MAX_LOCK_SECONDS)
            state.lockedUntil = now.plusSeconds(backoff)
        }
    }

    fun clear(key: String) {
        states.remove(key)
    }

    private companion object {
        const val MAX_FAILURES = 5
        const val WINDOW_SECONDS = 900L
        const val BASE_LOCK_SECONDS = 30L
        const val MAX_LOCK_SECONDS = 900L
        const val MAX_BACKOFF_STEPS = 4
    }
}
