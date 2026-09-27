package com.afrithecus.brainbox.api.common.state

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps the shared store so an unreachable backend degrades instead of failing requests: the
 * primary is tried first, and a failure falls through to the in-process store for that call,
 * with the failure rate logged (bounded, so an outage does not flood the log).
 *
 * The decorator does not decide policy — it cannot know whether a feature may proceed
 * without an answer. It always produces a value when the fallback can, and the facades apply
 * their own [FailureMode] when even that is impossible.
 */
class ResilientSharedStateStore(
    private val primary: SharedStateStore,
    private val fallback: SharedStateStore,
    private val policy: SharedStatePolicy,
) : SharedStateStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = AtomicLong(0)
    private val lastLoggedAt = AtomicLong(0)

    override val shared: Boolean get() = primary.shared

    override fun increment(key: String, ttl: Duration): Long =
        attempt("increment") { primary.increment(key, ttl) } ?: fallback.increment(key, ttl)

    override fun getLong(key: String): Long? = attempt("getLong") { primary.getLong(key) }

    override fun putLong(key: String, value: Long, ttl: Duration) {
        attempt("putLong") { primary.putLong(key, value, ttl); Unit }
    }

    override fun delete(key: String) {
        attempt("delete") { primary.delete(key) }
        // The fallback is cleared too, so a recovered primary does not resurrect a stale value.
        runCatching { fallback.delete(key) }
    }

    override fun addToSet(key: String, member: String, ttl: Duration): Long? =
        attempt("addToSet") { primary.addToSet(key, member, ttl) } ?: fallback.addToSet(key, member, ttl)

    override fun removeFromSet(key: String, member: String) {
        attempt("removeFromSet") { primary.removeFromSet(key, member) }
        runCatching { fallback.removeFromSet(key, member) }
    }

    override fun setSize(key: String): Long =
        attempt("setSize") { primary.setSize(key) } ?: fallback.setSize(key)

    /** Runs [block], returning null (and logging, rate-limited) when the backend failed. */
    private fun <T> attempt(operation: String, block: () -> T): T? = try {
        block()
    } catch (failure: Exception) {
        val total = failures.incrementAndGet()
        val now = System.currentTimeMillis()
        val previous = lastLoggedAt.get()
        if (previous == 0L || now - previous > LOG_INTERVAL_MS) {
            lastLoggedAt.set(now)
            log.error(
                "shared state backend failed on {} ({} failures so far), falling back to the in-process store: {}",
                operation,
                total,
                failure.message,
            )
        }
        null
    }

    private companion object {
        const val LOG_INTERVAL_MS = 30_000L
    }
}
