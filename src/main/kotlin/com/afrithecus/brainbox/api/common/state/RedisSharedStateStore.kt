package com.afrithecus.brainbox.api.common.state

import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

/**
 * Redis-backed shared state. Every key is prefixed (`app.redis.key-prefix`) so one Redis
 * database can serve several environments, and every write carries its own TTL, so a
 * forgotten key expires instead of accumulating.
 *
 * Failures are not swallowed here: they propagate to the per-feature facade, which applies
 * the deployment's allow/deny policy for that feature.
 */
class RedisSharedStateStore(
    private val redis: StringRedisTemplate,
    private val keyPrefix: String,
) : SharedStateStore {

    override val shared: Boolean = true

    private fun key(name: String) = keyPrefix + name

    override fun increment(name: String, ttl: Duration): Long {
        val key = key(name)
        val value = redis.opsForValue().increment(key) ?: 0L
        // A fresh counter gets its window; an existing one keeps the TTL it was born with,
        // so a burst cannot extend a lock window indefinitely.
        if (value == 1L) redis.expire(key, ttl)
        return value
    }

    override fun getLong(name: String): Long? = redis.opsForValue().get(key(name))?.toLongOrNull()

    override fun putLong(name: String, value: Long, ttl: Duration) {
        redis.opsForValue().set(key(name), value.toString(), ttl)
    }

    override fun delete(name: String) {
        redis.delete(key(name))
    }

    override fun addToSet(name: String, member: String, ttl: Duration): Long? {
        val key = key(name)
        redis.opsForSet().add(key, member)
        redis.expire(key, ttl)
        return redis.opsForSet().size(key)
    }

    override fun removeFromSet(name: String, member: String) {
        redis.opsForSet().remove(key(name), member)
    }

    override fun setSize(name: String): Long = redis.opsForSet().size(key(name)) ?: 0L
}
