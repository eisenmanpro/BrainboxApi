package com.afrithecus.brainbox.api.common.resilience

import org.slf4j.LoggerFactory
import java.util.concurrent.ThreadLocalRandom

/**
 * Small bounded retry with exponential backoff and jitter for transient outbound
 * calls (resource temporarily unavailable, 429/5xx, socket timeout). Kept
 * dependency-free so the runtime classpath stays Spring-Boot-starters only.
 */
object Retry {

    private val log = LoggerFactory.getLogger(Retry::class.java)

    fun <T> withBackoff(
        attempts: Int = 3,
        initialDelayMillis: Long = 100,
        multiplier: Double = 2.0,
        maxDelayMillis: Long = 2_000,
        isRetryable: (Exception) -> Boolean = { true },
        block: () -> T,
    ): T {
        require(attempts >= 1) { "attempts must be at least 1" }
        var delay = initialDelayMillis.coerceAtLeast(0)
        var failure: Exception? = null
        for (attempt in 1..attempts) {
            try {
                return block()
            } catch (error: Exception) {
                failure = error
                if (attempt == attempts || !isRetryable(error)) throw error
                val jitter = if (delay > 0) ThreadLocalRandom.current().nextLong(delay / 2 + 1) else 0
                val wait = delay + jitter
                log.debug("Retry {}/{} in {}ms: {}", attempt, attempts, wait, error.message)
                try {
                    Thread.sleep(wait)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                delay = (delay * multiplier).toLong().coerceAtMost(maxDelayMillis)
            }
        }
        throw failure ?: IllegalStateException("retry block failed without an error")
    }
}
