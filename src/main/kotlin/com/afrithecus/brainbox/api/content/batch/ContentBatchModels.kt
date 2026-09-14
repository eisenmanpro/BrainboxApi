package com.afrithecus.brainbox.api.content.batch

/**
 * Phase 7.5e Tier 1 batch producer. The producer turns the seeded Tier 0
 * curriculum topics into durable generation jobs so the worker can generate,
 * project and (when clean) auto-approve a starter library. It never calls the
 * provider and never writes a client-facing table: it only enqueues through
 * GenerationJobService, and everything downstream runs the router -> worker ->
 * projection/auto-approval path.
 *
 * Task types are limited to what the projection layer can render today:
 * NOTES and QUIZ both become learning_posts. FLASHCARDS is deferred because the
 * client has no flashcards content type, and BOOK-like extras stay in 7.6.
 */
const val CONTENT_BATCH_DEFAULT_LIMIT: Int = 50
const val CONTENT_BATCH_MAX_LIMIT: Int = 500
const val CONTENT_BATCH_COUNTRY_CODE: String = "KE"
const val CONTENT_BATCH_CURRICULUM: String = "CBC"
const val CONTENT_BATCH_DEFAULT_LANGUAGE: String = "en"
const val CONTENT_BATCH_DEFAULT_STANDARD_VERSION: String = "v1"

/** The only task types the producer enqueues today. */
val CONTENT_BATCH_TASK_TYPES: List<String> = listOf("NOTES", "QUIZ")

/** A short, standard instruction stored on every produced request. */
const val CONTENT_BATCH_INSTRUCTION: String =
    "Generate grade-appropriate Kenya CBC study content for this topic, following the BrainBox standard."

/**
 * One batch request. [gradeLevel] is required; everything else has a safe default.
 * [limit] bounds how many topic concepts one call processes.
 */
data class ContentBatchRequest(
    val gradeLevel: String,
    val subject: String? = null,
    val taskTypes: List<String> = CONTENT_BATCH_TASK_TYPES,
    val language: String = CONTENT_BATCH_DEFAULT_LANGUAGE,
    val standardVersion: String = CONTENT_BATCH_DEFAULT_STANDARD_VERSION,
    val limit: Int = CONTENT_BATCH_DEFAULT_LIMIT,
)

/** What one batch call did, so an operator or the startup log can size and audit it. */
data class ContentBatchSummary(
    val subject: String?,
    val gradeLevel: String,
    val taskTypes: List<String>,
    /** Total leaf topics matching the grade/subject before the [limit] is applied. */
    val conceptsMatched: Int,
    /** Leaf topics actually processed by this call. */
    val conceptsQueued: Int,
    /** Jobs that were newly created (no existing row for the generation key). */
    val jobsEnqueued: Int,
    /** Jobs reused because a row already existed for the generation key. */
    val jobsAlreadyPresent: Int,
    /** True when more topics matched than the limit allowed. */
    val truncated: Boolean,
)

/** Candidate count for the operator pre-flight call. */
data class ContentBatchCandidates(
    val gradeLevel: String,
    val subject: String?,
    val conceptsMatched: Int,
)
