package com.afrithecus.brainbox.api.push.web

/** FCM device registration (docs/ongoing/api_push_changes.md). The actor is the bearer token. */
data class DeviceRegistrationRequest(
    val token: String,
    val platform: String = "ANDROID",
    val appVersion: String? = null,
)

data class DeviceUnregisterRequest(val token: String)

data class PushAckPayload(val success: Boolean = true, val message: String = "")
