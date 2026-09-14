package com.afrithecus.brainbox.api.content.batch

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.GenerationJobService
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Phase 7.5e Tier 1 batch producer. It resolves the leaf `topic` concepts of the
 * seeded Tier 0 skeleton and enqueues one durable generation job per topic x task
 * type. It is deliberately provider-free: with `app.ai.enabled=false` the jobs are
 * still enqueued, and the worker fails and retries them until the provider is
 * enabled (the queue's bounded retry budget turns that into FAILED after
 * `app.content.worker.max-attempts`).
 *
 * Enqueue is idempotent per generation key, so re-running the batch reuses the
 * existing `generation_jobs` row instead of duplicating work.
 */
@Service
class ContentBatchService(
    private val concepts: ConceptRepository,
    private val generationJobs: GenerationJobRepository,
    private val jobService: GenerationJobService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The leaf topics of the Tier 0 skeleton for [gradeLevel]: a concept with a
     * non-null parent (it is a strand/sub-strand child) and no children of its own,
     * mapped in curriculum_map for Kenya CBC at this grade. [subject] is optional.
     */
    fun resolveTopicConcepts(gradeLevel: String, subject: String? = null): List<ConceptEntity> =
        concepts.findLeafTopics(
            countryCode = CONTENT_BATCH_COUNTRY_CODE,
            curriculum = CONTENT_BATCH_CURRICULUM,
            gradeLevel = requiredGrade(gradeLevel),
            subject = normalizedSubject(subject),
        )

    /** Candidate count for the operator pre-flight call. */
    fun countCandidates(gradeLevel: String, subject: String? = null): Int =
        resolveTopicConcepts(gradeLevel, subject).size

    /**
     * Enqueues one job for every selected topic x task type. `conceptsMatched` is
     * the full candidate count (so a caller can size a limited run); `truncated`
     * is true when the limit hid candidates.
     */
    fun enqueueBatch(request: ContentBatchRequest): ContentBatchSummary {
        val gradeLevel = requiredGrade(request.gradeLevel)
        val subject = normalizedSubject(request.subject)
        val taskTypes = normalizedTaskTypes(request.taskTypes)
        val language = request.language.trim().lowercase().ifEmpty { CONTENT_BATCH_DEFAULT_LANGUAGE }
        val standardVersion = request.standardVersion.trim().ifEmpty { CONTENT_BATCH_DEFAULT_STANDARD_VERSION }
        val limit = normalizedLimit(request.limit)

        val matched = resolveTopicConcepts(gradeLevel, subject)
        val selected = matched.take(limit)
        val truncated = selected.size < matched.size

        var enqueued = 0
        var alreadyPresent = 0
        for (topic in selected) {
            for (taskType in taskTypes) {
                val generationRequest = buildRequest(topic, taskType, gradeLevel, language, standardVersion)
                val existing = generationJobs
                    .findAllByGenerationKeyOrderByCreatedAtAsc(generationRequest.generationKey)
                    .isNotEmpty()
                jobService.enqueue(generationRequest)
                if (existing) alreadyPresent++ else enqueued++
            }
        }

        val summary = ContentBatchSummary(
            subject = subject,
            gradeLevel = gradeLevel,
            taskTypes = taskTypes,
            conceptsMatched = matched.size,
            conceptsQueued = selected.size,
            jobsEnqueued = enqueued,
            jobsAlreadyPresent = alreadyPresent,
            truncated = truncated,
        )
        log.info(
            "Tier 1 batch enqueued: subject={} gradeLevel={} taskTypes={} conceptsMatched={} conceptsQueued={} jobsEnqueued={} jobsAlreadyPresent={} truncated={}",
            summary.subject,
            summary.gradeLevel,
            summary.taskTypes,
            summary.conceptsMatched,
            summary.conceptsQueued,
            summary.jobsEnqueued,
            summary.jobsAlreadyPresent,
            summary.truncated,
        )
        return summary
    }

    /** Builds the deterministic provider request for one topic and task type. */
    fun buildRequest(
        topic: ConceptEntity,
        taskType: String,
        gradeLevel: String,
        language: String,
        standardVersion: String,
    ): GenerationRequest = GenerationRequest(
        generationKey = generationKey(gradeLevel, topic.code, taskType, language, standardVersion),
        taskType = taskType,
        taskTypeLabel = taskTypeLabel(taskType),
        conceptCode = topic.code,
        conceptName = topic.name,
        subject = topic.subject,
        gradeLevel = gradeLevel,
        language = language,
        standardVersion = standardVersion,
        difficulty = null,
        notes = CONTENT_BATCH_INSTRUCTION,
    )

    /**
     * Deterministic key: `ke:cbc:{gradeSlug}:{conceptCode}:{taskType}:{language}:{standardVersion}`,
     * lowercased and sanitised so any character outside `[a-z0-9:-]` becomes `-`,
     * then truncated to 256 chars. Stable across runs, so enqueue is idempotent.
     */
    fun generationKey(
        gradeLevel: String,
        conceptCode: String,
        taskType: String,
        language: String,
        standardVersion: String,
    ): String {
        val gradeSlug = gradeLevel.lowercase().replace(NON_SLUG, "")
        val raw = "ke:cbc:$gradeSlug:$conceptCode:${taskType.lowercase()}:$language:$standardVersion"
        return raw.lowercase().replace(NON_KEY_CHAR, "-").take(MAX_KEY_CHARS)
    }

    private fun taskTypeLabel(taskType: String): String =
        taskType.lowercase().replaceFirstChar { it.uppercase() }

    private fun requiredGrade(gradeLevel: String): String =
        gradeLevel.trim().ifEmpty { throw invalidArgument("gradeLevel is required") }

    private fun normalizedSubject(subject: String?): String? =
        subject?.trim()?.takeIf { it.isNotEmpty() }

    private fun normalizedTaskTypes(taskTypes: List<String>?): List<String> {
        val requested = taskTypes
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        val effective = requested.ifEmpty { CONTENT_BATCH_TASK_TYPES }
        val unknown = effective.filterNot { it in CONTENT_BATCH_TASK_TYPES }
        if (unknown.isNotEmpty()) {
            throw invalidArgument(
                "unknown task type(s): " + unknown.joinToString(", ") +
                    "; supported: " + CONTENT_BATCH_TASK_TYPES.joinToString(", ")
            )
        }
        return effective
    }

    private fun normalizedLimit(limit: Int): Int {
        if (limit <= 0) throw invalidArgument("limit must be a positive integer")
        return limit.coerceAtMost(CONTENT_BATCH_MAX_LIMIT)
    }

    private companion object {
        const val MAX_KEY_CHARS = 256
        val NON_SLUG = Regex("[^a-z0-9]")
        val NON_KEY_CHAR = Regex("[^a-z0-9:-]")
    }
}
