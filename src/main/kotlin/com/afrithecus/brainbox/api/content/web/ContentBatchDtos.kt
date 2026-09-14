package com.afrithecus.brainbox.api.content.web

// ---------------------------------------------------------------------------
// Phase 7.5e Tier 1 batch producer admin surface. An operator sizes a run with
// GET /admin/content/batch/candidates and then enqueues it with
// POST /admin/content/batch. The response is the shared ContentBatchSummary.
// ---------------------------------------------------------------------------

/** Body of POST /admin/content/batch. A blank gradeLevel is rejected as invalid argument. */
data class BatchEnqueueRequest(
    val subject: String? = null,
    val gradeLevel: String? = null,
    /** Defaults to [NOTES, QUIZ]; unknown types are rejected. */
    val taskTypes: List<String>? = null,
    val language: String? = null,
    val standardVersion: String? = null,
    val limit: Int? = null,
)
