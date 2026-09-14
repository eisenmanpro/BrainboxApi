package com.afrithecus.brainbox.api.content.web

// ---------------------------------------------------------------------------
// Phase 7.5a generation job submit/poll payloads. A teacher submits the request
// once (POST teacher/content/generate) and polls the job (GET
// teacher/content/jobs/{jobId}); run-mode=api returns QUEUED and a separate
// worker drains, so the old synchronous expectation is replaced by submit+poll.
// ---------------------------------------------------------------------------

/** Body of POST /teacher/content/generate. Required fields are validated in the controller. */
data class GenerateContentRequest(
    val generationKey: String? = null,
    val taskType: String? = null,
    val subject: String? = null,
    val gradeLevel: String? = null,
    val language: String? = null,
    val standardVersion: String? = null,
    val conceptCode: String? = null,
    val difficulty: Int? = null,
    val notes: String? = null,
)

/** One generation job as returned by the submit and poll endpoints. */
data class GenerationJobPayload(
    val id: String,
    val generationKey: String,
    val taskType: String,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val lastError: String?,
    val runId: String?,
    val unitId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
