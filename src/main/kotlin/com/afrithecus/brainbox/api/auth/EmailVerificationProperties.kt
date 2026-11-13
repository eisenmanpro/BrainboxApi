package com.afrithecus.brainbox.api.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Email verification settings (gaps.md item 3). Enabled by default because it is the signup gate;
 * the transport is selected by app.mail.provider, and the code lifecycle belongs to the API.
 */
@ConfigurationProperties(prefix = "app.auth.email-verification")
data class EmailVerificationProperties(
    val enabled: Boolean = true,
    val codeTtl: Duration = Duration.ofMinutes(10),
    val resendCooldown: Duration = Duration.ofSeconds(60),
    val maxAttempts: Int = 5,
    val maxRequestsPerHour: Int = 5,
)
