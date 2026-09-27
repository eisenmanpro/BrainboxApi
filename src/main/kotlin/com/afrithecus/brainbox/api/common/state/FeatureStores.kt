package com.afrithecus.brainbox.api.common.state

import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Rate-limit counters. With [FailureMode.ALLOW] an outage reports `null` — "proceed" — because
 * locking every user out of the product is worse than briefly losing cross-node counting; the
 * caller falls back to its own in-process counter, so limits degrade to per-node rather than
 * disappearing. With [FailureMode.DENY] the caller is told to refuse.
 */
class RateLimitStore(
    private val store: SharedStateStore,
    private val failureMode: FailureMode,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The new count, or null when the store could not answer and the policy is ALLOW. */
    fun increment(key: String, ttl: Duration): Long? = try {
        store.increment(key, ttl)
    } catch (failure: Exception) {
        report("increment", failure)
        null
    }

    fun get(key: String): Long? = try {
        store.getLong(key)
    } catch (failure: Exception) {
        report("get", failure)
        null
    }

    fun put(key: String, value: Long, ttl: Duration) {
        try {
            store.putLong(key, value, ttl)
        } catch (failure: Exception) {
            report("put", failure)
        }
    }

    fun clear(key: String) {
        try {
            store.delete(key)
        } catch (failure: Exception) {
            report("clear", failure)
        }
    }

    /** True when a missing answer must be treated as a refusal for this deployment. */
    fun denyOnFailure(): Boolean = failureMode == FailureMode.DENY

    private fun report(operation: String, failure: Exception) {
        log.error("rate-limit store {} failed ({}): {}", operation, failureMode, failure.message)
    }
}

/**
 * Advisory presence (how many peers are in a live class on any node). Always ALLOW on
 * failure: a peer count is not worth ejecting anyone over, and the caller keeps its
 * in-process count.
 */
class PresenceStore(
    private val store: SharedStateStore,
    private val failureMode: FailureMode,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun join(key: String, member: String, ttl: Duration): Long? = try {
        store.addToSet(key, member, ttl)
    } catch (failure: Exception) {
        report("join", failure)
        null
    }

    fun leave(key: String, member: String) {
        try {
            store.removeFromSet(key, member)
        } catch (failure: Exception) {
            report("leave", failure)
        }
    }

    fun count(key: String): Long? = try {
        store.setSize(key)
    } catch (failure: Exception) {
        report("count", failure)
        null
    }

    fun denyOnFailure(): Boolean = failureMode == FailureMode.DENY

    private fun report(operation: String, failure: Exception) {
        log.warn("presence store {} failed ({}): {}", operation, failureMode, failure.message)
    }
}
