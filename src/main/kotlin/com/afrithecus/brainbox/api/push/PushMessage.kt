package com.afrithecus.brainbox.api.push

/** The data payload the client renders (docs/ongoing/api_push_changes.md). */
data class PushMessage(
    val title: String,
    val message: String,
    val type: String? = null,
    val actionRoute: String? = null,
    val actionLabel: String? = null,
    val urgency: String? = null,
    /** Extra string data, e.g. the attendance `status`. */
    val metadata: Map<String, String> = emptyMap(),
)
