package com.afrithecus.brainbox.api.common.state

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The small slice of shared state the API needs to behave identically on more than one
 * node: counters (rate limits) and expiring sets (presence).
 *
 * Every operation is best-effort and may throw when the backend is unreachable; the
 * per-feature facades ([RateLimitStore], [PresenceStore]) decide whether that means "allow"
 * or "deny". Keeping the surface this small is deliberate — it is what makes moving from
 * in-process maps to Redis a configuration change rather than a rewrite.
 */
interface SharedStateStore {

    /** True when the store is shared between nodes (Redis); false for the in-process one. */
    val shared: Boolean

    /** Adds one to a counter, creating it with [ttl] when absent. Returns the new value. */
    fun increment(key: String, ttl: Duration): Long

    fun getLong(key: String): Long?

    fun putLong(key: String, value: Long, ttl: Duration)

    fun delete(key: String)

    /** Adds [member] to an expiring set; returns the set size, or null when unsupported. */
    fun addToSet(key: String, member: String, ttl: Duration): Long?

    fun removeFromSet(key: String, member: String)

    fun setSize(key: String): Long
}

/**
 * The default: process-local maps with a lazy expiry check, which is exactly the behaviour
 * the API had before a shared store existed. Values never outlive their TTL by more than the
 * sweep interval on a read.
 */
class InMemorySharedStateStore(private val clock: Clock) : SharedStateStore {

    override val shared: Boolean = false

    private data class Entry(var value: Long, var expiresAtMillis: Long)
    private data class SetEntry(val members: MutableMap<String, Long>)

    private val counters = ConcurrentHashMap<String, Entry>()
    private val sets = ConcurrentHashMap<String, SetEntry>()

    override fun increment(key: String, ttl: Duration): Long {
        val now = clock.millis()
        val entry = counters.compute(key) { _, existing ->
            if (existing == null || existing.expiresAtMillis <= now) {
                Entry(1L, now + ttl.toMillis())
            } else {
                existing.value += 1
                existing
            }
        }!!
        return entry.value
    }

    override fun getLong(key: String): Long? {
        val entry = counters[key] ?: return null
        if (entry.expiresAtMillis <= clock.millis()) {
            counters.remove(key, entry)
            return null
        }
        return entry.value
    }

    override fun putLong(key: String, value: Long, ttl: Duration) {
        counters[key] = Entry(value, clock.millis() + ttl.toMillis())
    }

    override fun delete(key: String) {
        counters.remove(key)
        sets.remove(key)
    }

    override fun addToSet(key: String, member: String, ttl: Duration): Long {
        val expiresAt = clock.millis() + ttl.toMillis()
        val entry = sets.computeIfAbsent(key) { SetEntry(ConcurrentHashMap()) }
        entry.members[member] = expiresAt
        val now = clock.millis()
        entry.members.entries.removeIf { it.value <= now }
        if (entry.members.isEmpty()) sets.remove(key, entry)
        return entry.members.size.toLong()
    }

    override fun removeFromSet(key: String, member: String) {
        val entry = sets[key] ?: return
        entry.members.remove(member)
        if (entry.members.isEmpty()) sets.remove(key, entry)
    }

    override fun setSize(key: String): Long {
        val entry = sets[key] ?: return 0L
        val now = clock.millis()
        entry.members.entries.removeIf { it.value <= now }
        if (entry.members.isEmpty()) {
            sets.remove(key, entry)
            return 0L
        }
        return entry.members.size.toLong()
    }
}
