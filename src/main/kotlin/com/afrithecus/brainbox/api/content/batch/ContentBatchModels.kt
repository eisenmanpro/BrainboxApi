package com.afrithecus.brainbox.api.content.batch

/**
 * Phase 7.5e Tier 1 batch producer. The producer turns the seeded Tier 0
 * curriculum topics into durable generation jobs so the worker can generate,
 * project and (when clean) auto-approve a starter library. It never calls the
 * provider and never writes a client-facing table: it only enqueues through
 * GenerationJobService, and everything downstream runs the router -> worker ->
 * projection/auto-approval path.
 *
 * Topic task types (NOTES, QUIZ) are one job per leaf topic. Phase 7.6a adds the
 * subject x grade shelf task types (PRACTICE_PAPER, STUDY_GUIDE): one or two
 * original practice papers and one study guide per subject-grade, keyed
 * deterministically and projected to the exams and learning-post read paths.
 * FLASHCARDS is deferred because the client has no flashcards content type.
 */
const val CONTENT_BATCH_DEFAULT_LIMIT: Int = 50
const val CONTENT_BATCH_MAX_LIMIT: Int = 500
const val CONTENT_BATCH_COUNTRY_CODE: String = "KE"
const val CONTENT_BATCH_CURRICULUM: String = "CBC"
const val CONTENT_BATCH_DEFAULT_LANGUAGE: String = "en"
const val CONTENT_BATCH_DEFAULT_STANDARD_VERSION: String = "v1"

/** Default and maximum practice papers produced for one subject x grade shelf. */
const val CONTENT_BATCH_DEFAULT_PRACTICE_PAPERS: Int = 1
const val CONTENT_BATCH_MAX_PRACTICE_PAPERS: Int = 2

/** Topic-level task types: one job per leaf topic x type (7.5e, unchanged). */
val CONTENT_BATCH_TOPIC_TASK_TYPES: List<String> = listOf("NOTES", "QUIZ")

/** Subject x grade shelf task types: papers and guides (7.6a). */
val CONTENT_BATCH_SHELF_TASK_TYPES: List<String> = listOf("PRACTICE_PAPER", "STUDY_GUIDE")

/** Every task type the producer accepts; anything else is invalid argument. */
val CONTENT_BATCH_TASK_TYPES: List<String> =
    CONTENT_BATCH_TOPIC_TASK_TYPES + CONTENT_BATCH_SHELF_TASK_TYPES

/** A bare batch request still produces the topic-level library only. */
val CONTENT_BATCH_DEFAULT_TASK_TYPES: List<String> = CONTENT_BATCH_TOPIC_TASK_TYPES

/** A short, standard instruction stored on every produced request. */
const val CONTENT_BATCH_INSTRUCTION: String =
    "Generate grade-appropriate Kenya CBC study content for this topic, following the BrainBox standard."

/** Shelf instruction: an original paper in the BrainBox style, never a national paper. */
const val CONTENT_BATCH_PRACTICE_PAPER_INSTRUCTION: String =
    "Generate an original BrainBox practice paper covering the whole subject-grade band, " +
        "following the BrainBox standard. Write new questions only: never reproduce, quote or " +
        "attribute a KNEC or KICD examination paper."

/** Shelf instruction: a readable guide covering the band's topics. */
const val CONTENT_BATCH_STUDY_GUIDE_INSTRUCTION: String =
    "Generate an original BrainBox study guide covering the whole subject-grade band, " +
        "following the BrainBox standard, with explained steps and nested check questions."

/**
 * One batch request. [gradeLevel] is required; everything else has a safe default.
 * [limit] bounds how many topic concepts one call processes. [practicePapers]
 * bounds how many practice papers one subject x grade shelf produces (1 or 2).
 */
data class ContentBatchRequest(
    val gradeLevel: String,
    val subject: String? = null,
    val taskTypes: List<String> = CONTENT_BATCH_DEFAULT_TASK_TYPES,
    val language: String = CONTENT_BATCH_DEFAULT_LANGUAGE,
    val standardVersion: String = CONTENT_BATCH_DEFAULT_STANDARD_VERSION,
    val limit: Int = CONTENT_BATCH_DEFAULT_LIMIT,
    val practicePapers: Int = CONTENT_BATCH_DEFAULT_PRACTICE_PAPERS,
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
    /**
     * H3 backpressure: true when the daily generation budget stopped this run
     * early. The jobs already enqueued are kept; the endpoint still returns 200.
     */
    val budgetStopped: Boolean = false,
    /**
     * Topic x task-type slots this call did not enqueue because the budget stopped
     * it (0 when the run completed). Does not include candidates hidden by [truncated].
     */
    val remainingCandidates: Int = 0,
    /** Phase 7.6a subject x grade shelf jobs (papers + guides) newly created. */
    val shelfJobsEnqueued: Int = 0,
    /** Phase 7.6a subject x grade shelf jobs reused because the key already existed. */
    val shelfJobsAlreadyPresent: Int = 0,
)

/** Candidate count for the operator pre-flight call. */
data class ContentBatchCandidates(
    val gradeLevel: String,
    val subject: String?,
    val conceptsMatched: Int,
)
