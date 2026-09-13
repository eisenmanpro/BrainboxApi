package com.afrithecus.brainbox.api.push

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** FCM sender settings (docs/ongoing/api_push_changes.md). Disabled without credentials. */
@ConfigurationProperties(prefix = "app.push.fcm")
data class PushProperties(
    val enabled: Boolean = false,
    /** Optional overrides; otherwise read from the service-account JSON. */
    val projectId: String = "",
    val serviceAccountFile: String = "",
    val tokenUri: String = "https://oauth2.googleapis.com/token",
    /** Must match the Android manifest default notification channel. */
    val channelId: String = "system_notifications_channel",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(5),
)
