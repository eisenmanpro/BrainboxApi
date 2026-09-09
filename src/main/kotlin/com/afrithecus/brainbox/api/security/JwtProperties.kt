package com.afrithecus.brainbox.api.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * JWT settings. Secret is a Base64-encoded HMAC-SHA256 key, supplied per
 * environment via JWT_SECRET (dev default in application.yml only).
 */
@ConfigurationProperties(prefix = "app.security.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String = "brainbox-api",
    val accessTokenTtl: Duration = Duration.ofHours(24),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
)
