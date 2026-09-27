package com.afrithecus.brainbox.api.common.state

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration

/**
 * Shared state wiring. `app.redis.enabled=false` (the default) keeps every counter and set
 * in process, which is what a single-node deployment has always done. Setting it to true —
 * with the connection settings — moves that state into Redis, and the rest of the code does
 * not change because it only ever talks to [SharedStateStore].
 *
 * The Redis connection is built here rather than left to Boot's auto-configuration so that
 * enabling Redis is explicit, and so the failure mode of every feature is decided in one
 * place ([SharedStatePolicy]).
 */
@Configuration
class SharedStateConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun inMemorySharedStateStore(clock: Clock): InMemorySharedStateStore = InMemorySharedStateStore(clock)

    /**
     * The store the application uses. With Redis enabled the Redis store is wrapped in a
     * resilient decorator: an unreachable Redis must not take the API down, it must fall
     * back to the in-process store and say so.
     */
    // Primary: the in-process store is also a SharedStateStore (it is the failover target),
    // so one of the two must be the injection default.
    @Bean
    @Primary
    fun sharedStateStore(
        inMemory: InMemorySharedStateStore,
        policy: SharedStatePolicy,
        @Value("\${app.redis.enabled:false}") enabled: Boolean,
        @Value("\${app.redis.key-prefix:brainbox:}") keyPrefix: String,
        // ObjectProvider, not a nullable parameter: the template only exists when Redis is
        // enabled, and an optional dependency must not fail the context when it does not.
        redisTemplate: ObjectProvider<StringRedisTemplate>,
    ): SharedStateStore {
        val redis = redisTemplate.ifAvailable
        if (!enabled || redis == null) {
            log.info("shared state: in-process (set app.redis.enabled=true to share it between nodes)")
            return inMemory
        }
        log.info("shared state: Redis with key prefix '{}'", keyPrefix)
        return ResilientSharedStateStore(RedisSharedStateStore(redis, keyPrefix), inMemory, policy)
    }

    /** Built only when Redis is enabled, so a deployment without it never opens a connection. */
    @Bean
    @ConditionalOnProperty(name = ["app.redis.enabled"], havingValue = "true")
    fun redisConnectionFactory(
        @Value("\${app.redis.host:localhost}") host: String,
        @Value("\${app.redis.port:6379}") port: Int,
        @Value("\${app.redis.database:0}") database: Int,
        @Value("\${app.redis.password:}") password: String,
        @Value("\${app.redis.ssl:false}") ssl: Boolean,
        @Value("\${app.redis.timeout-millis:2000}") timeoutMillis: Long,
        @Value("\${app.redis.username:}") username: String,
    ): LettuceConnectionFactory {
        val standalone = RedisStandaloneConfiguration(host, port).apply {
            this.database = database
            username.trim().takeIf { it.isNotEmpty() }?.let { this.username = it }
            password.trim().takeIf { it.isNotEmpty() }?.let { this.password = RedisPassword.of(it) }
        }
        val client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(timeoutMillis))
            .apply { if (ssl) useSsl() }
            .build()
        return LettuceConnectionFactory(standalone, client)
    }

    @Bean
    @ConditionalOnProperty(name = ["app.redis.enabled"], havingValue = "true")
    fun sharedStateRedisTemplate(factory: RedisConnectionFactory): StringRedisTemplate =
        StringRedisTemplate(factory)

    @Bean
    fun rateLimitStore(store: SharedStateStore, policy: SharedStatePolicy): RateLimitStore =
        RateLimitStore(store, policy.rateLimitFailureMode())

    @Bean
    fun presenceStore(store: SharedStateStore, policy: SharedStatePolicy): PresenceStore =
        PresenceStore(store, policy.presenceFailureMode())
}
