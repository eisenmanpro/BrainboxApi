package com.afrithecus.brainbox.api.security

import org.springframework.boot.context.properties.ConfigurationProperties

/** Token-bucket rate limiting (per client IP) applied ahead of the API. */
@ConfigurationProperties(prefix = "app.security.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val capacity: Int = 120,
    val refillPerSecond: Int = 2,
)
