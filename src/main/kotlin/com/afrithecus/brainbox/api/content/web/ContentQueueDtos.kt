package com.afrithecus.brainbox.api.content.web

// ---------------------------------------------------------------------------
// H2 generation queue runtime surface admin payloads. GET /admin/content/queue
// returns the live pause/source policy plus queue depth, overall and per source.
// The pause/resume/sources writes return the same summary so an operator sees the
// new state in one round trip.
// ---------------------------------------------------------------------------

/** Live worker policy plus queue depth, returned by every /admin/content/queue call. */
data class ContentQueueSummary(
    /** Effective `content_worker_paused` value (policy override or app default). */
    val paused: Boolean,
    /** Effective enabled sources, highest priority first. */
    val sources: List<String>,
    /** generation_jobs count per status, all canonical statuses present. */
    val depth: Map<String, Long>,
    /** generation_jobs count per source and status, all canonical sources present. */
    val bySource: Map<String, Map<String, Long>>,
)

/** Body of PUT /admin/content/queue/sources. Unknown names are rejected as invalid argument. */
data class ContentQueueSourcesRequest(
    val sources: List<String>? = null,
)
