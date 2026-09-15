package com.afrithecus.brainbox.api.content.batch

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.content.GenerationJobService
import com.afrithecus.brainbox.api.content.GenerationJobSource
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Phase 7.5e Tier 1 batch producer. It resolves the leaf `topic` concepts of the
 * seeded Tier 0 skeleton and enqueues one durable generation job per topic x task
 * type. It is deliberately provider-free: with `app.ai.enabled=false` the jobs are
 * still enqueued, and the worker fails and retries them until the provider is
 * enabled (the queue's bounded retry budget turns that into FAILED after
 * `app.content.worker.max-attempts`).
 *
 * Phase 7.6a extends the producer with a subject x grade shelf path: one or two
 * original PRACTICE_PAPERs and one STUDY_GUIDE per subject-grade, keyed
 * deterministically (`ke:cbc:{grade}:{subject}:practice-paper:{n}:en:v1` and
 * `ke:cbc:{grade}:{subject}:study-guide:en:v1`). A shelf unit is anchored to the
 * subject's first leaf topic concept so the universal curriculum validator still
 * applies; the prompt covers the whole band.
 *
 * Enqueue is idempotent per generation key, so re-running the batch reuses the
 * existing `generation_jobs` row instead of duplicating work.
 *
 * H3: the loop stops at the first daily-budget rejection. The jobs already
 * enqueued are kept and committed, and the summary reports `budgetStopped` plus
 * the remaining candidate slots so the endpoint returns a 200 partial result
 * instead of failing the whole call once progress has been made.
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
     * Enqueues the selected topic x task-type jobs plus, when a shelf task type is
     * requested, one or two practice papers and one study guide per subject x grade.
     * `conceptsMatched` is the full candidate count (so a caller can size a limited
     * run); `truncated` is true when the limit hid candidates.
     */
    fun enqueueBatch(request: ContentBatchRequest): ContentBatchSummary {
        val gradeLevel = requiredGrade(request.gradeLevel)
        val subject = normalizedSubject(request.subject)
        val taskTypes = normalizedTaskTypes(request.taskTypes)
        val language = request.language.trim().lowercase().ifEmpty { CONTENT_BATCH_DEFAULT_LANGUAGE }
        val standardVersion = request.standardVersion.trim().ifEmpty { CONTENT_BATCH_DEFAULT_STANDARD_VERSION }
        val limit = normalizedLimit(request.limit)
        val practicePapers = normalizedPracticePapers(request.practicePapers)

        val topicTaskTypes = taskTypes.filter { it in CONTENT_BATCH_TOPIC_TASK_TYPES }
        val shelfTaskTypes = taskTypes.filter { it in CONTENT_BATCH_SHELF_TASK_TYPES }

        val matched = resolveTopicConcepts(gradeLevel, subject)
        val selected = matched.take(limit)
        val truncated = selected.size < matched.size

        val slots = mutableListOf<EnqueueSlot>()
        selected.forEach { topic ->
            topicTaskTypes.forEach { taskType ->
                slots += EnqueueSlot(
                    request = buildRequest(topic, taskType, gradeLevel, language, standardVersion),
                    topicId = topic.id,
                )
            }
        }
        if (shelfTaskTypes.isNotEmpty()) {
            resolveShelves(matched, subject).forEach { shelf ->
                if (SHELF_STUDY_GUIDE in shelfTaskTypes) {
                    slots += EnqueueSlot(
                        request = buildShelfRequest(
                            taskType = SHELF_STUDY_GUIDE,
                            subject = shelf.subject,
                            anchor = shelf.anchor,
                            gradeLevel = gradeLevel,
                            language = language,
                            standardVersion = standardVersion,
                            paperIndex = 1,
                        ),
                        topicId = null,
                    )
                }
                if (SHELF_PRACTICE_PAPER in shelfTaskTypes) {
                    for (index in 1..practicePapers) {
                        slots += EnqueueSlot(
                            request = buildShelfRequest(
                                taskType = SHELF_PRACTICE_PAPER,
                                subject = shelf.subject,
                                anchor = shelf.anchor,
                                gradeLevel = gradeLevel,
                                language = language,
                                standardVersion = standardVersion,
                                paperIndex = index,
                            ),
                            topicId = null,
                        )
                    }
                }
            }
        }

        var enqueued = 0
        var alreadyPresent = 0
        var processed = 0
        var shelfEnqueued = 0
        var shelfAlreadyPresent = 0
        var budgetStopped = false
        val processedTopics = linkedSetOf<UUID>()

        for (slot in slots) {
            val generationRequest = slot.request
            val existing = generationJobs
                .findAllByGenerationKeyOrderByCreatedAtAsc(generationRequest.generationKey)
                .isNotEmpty()
            try {
                jobService.enqueue(generationRequest, GenerationJobSource.BATCH)
            } catch (blocked: ApiException) {
                if (blocked.code != ApiErrorCode.TOO_MANY_REQUESTS) throw blocked
                // H3 backpressure: one over-budget job must not fail a call that has
                // already made progress, so keep the enqueued jobs and stop cleanly.
                budgetStopped = true
                log.warn(
                    "Tier 1 batch stopped at the daily generation budget after {} of {} job slots: {}",
                    processed,
                    slots.size,
                    blocked.message,
                )
                break
            }
            processed++
            slot.topicId?.let { processedTopics += it }
            if (existing) {
                alreadyPresent++
                if (slot.topicId == null) shelfAlreadyPresent++
            } else {
                enqueued++
                if (slot.topicId == null) shelfEnqueued++
            }
        }
        val remainingCandidates = slots.size - processed

        val summary = ContentBatchSummary(
            subject = subject,
            gradeLevel = gradeLevel,
            taskTypes = taskTypes,
            conceptsMatched = matched.size,
            conceptsQueued = processedTopics.size,
            jobsEnqueued = enqueued,
            jobsAlreadyPresent = alreadyPresent,
            truncated = truncated,
            budgetStopped = budgetStopped,
            remainingCandidates = remainingCandidates,
            shelfJobsEnqueued = shelfEnqueued,
            shelfJobsAlreadyPresent = shelfAlreadyPresent,
        )
        log.info(
            "Tier 1 batch enqueued: subject={} gradeLevel={} taskTypes={} conceptsMatched={} conceptsQueued={} " +
                "jobsEnqueued={} jobsAlreadyPresent={} shelfJobsEnqueued={} shelfJobsAlreadyPresent={} " +
                "truncated={} budgetStopped={} remainingCandidates={}",
            summary.subject,
            summary.gradeLevel,
            summary.taskTypes,
            summary.conceptsMatched,
            summary.conceptsQueued,
            summary.jobsEnqueued,
            summary.jobsAlreadyPresent,
            summary.shelfJobsEnqueued,
            summary.shelfJobsAlreadyPresent,
            summary.truncated,
            summary.budgetStopped,
            summary.remainingCandidates,
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
     * Builds the deterministic provider request for one subject x grade shelf item.
     * [anchor] anchors the unit to a resolvable concept + curriculum mapping, while
     * [conceptName] carries the human-readable shelf title so the projected exam or
     * post has a sensible heading and the prompt covers the whole band.
     */
    fun buildShelfRequest(
        taskType: String,
        subject: String,
        anchor: ConceptEntity,
        gradeLevel: String,
        language: String,
        standardVersion: String,
        paperIndex: Int = 1,
    ): GenerationRequest {
        val normalizedType = taskType.trim().uppercase()
        val isPaper = normalizedType == SHELF_PRACTICE_PAPER
        return GenerationRequest(
            generationKey = if (isPaper) {
                practicePaperKey(gradeLevel, subject, paperIndex, language, standardVersion)
            } else {
                studyGuideKey(gradeLevel, subject, language, standardVersion)
            },
            taskType = normalizedType,
            taskTypeLabel = taskTypeLabel(normalizedType),
            conceptCode = anchor.code,
            conceptName = shelfTitle(gradeLevel, subject, normalizedType, paperIndex),
            subject = subject,
            gradeLevel = gradeLevel,
            language = language,
            standardVersion = standardVersion,
            difficulty = null,
            notes = if (isPaper) CONTENT_BATCH_PRACTICE_PAPER_INSTRUCTION else CONTENT_BATCH_STUDY_GUIDE_INSTRUCTION,
        )
    }

    /** Deterministic key for one generated practice paper. */
    fun practicePaperKey(
        gradeLevel: String,
        subject: String,
        paperIndex: Int,
        language: String,
        standardVersion: String,
    ): String = shelfGenerationKey(gradeLevel, subject, PRACTICE_PAPER_SLUG, paperIndex, language, standardVersion)

    /** Deterministic key for the generated study guide of one subject x grade. */
    fun studyGuideKey(
        gradeLevel: String,
        subject: String,
        language: String,
        standardVersion: String,
    ): String = shelfGenerationKey(gradeLevel, subject, STUDY_GUIDE_SLUG, null, language, standardVersion)

    /**
     * Deterministic shelf key:
     * `ke:cbc:{gradeSlug}:{subjectSlug}:{taskSlug}[:{n}]:{language}:{standardVersion}`,
     * lowercased and sanitised so any character outside `[a-z0-9:-]` becomes `-`,
     * then truncated to 256 chars. Stable across runs, so enqueue is idempotent.
     */
    fun shelfGenerationKey(
        gradeLevel: String,
        subject: String,
        taskSlug: String,
        paperIndex: Int?,
        language: String,
        standardVersion: String,
    ): String {
        val gradeSlug = gradeLevel.lowercase().replace(NON_SLUG, "")
        val subjectSlug = subject.lowercase().replace(NON_KEY_CHAR, "-")
        val indexSegment = paperIndex?.let { ":" + it }.orEmpty()
        val raw = "ke:cbc:$gradeSlug:$subjectSlug:$taskSlug$indexSegment:$language:$standardVersion"
        return raw.lowercase().replace(NON_KEY_CHAR, "-").take(MAX_KEY_CHARS)
    }

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

    /**
     * The subject x grade shelves this batch covers. An explicit [subject] yields
     * one shelf; otherwise every subject present among the grade's leaf topics.
     * The anchor is the subject's first leaf topic (by authored sort order), which
     * anchors the shelf-level unit to a resolvable concept and mapping.
     */
    private fun resolveShelves(matched: List<ConceptEntity>, subject: String?): List<BatchShelf> {
        if (subject != null) {
            val anchor = matched.firstOrNull() ?: return emptyList()
            return listOf(BatchShelf(subject, anchor))
        }
        return matched.groupBy { it.subject }.map { (shelfSubject, topics) ->
            BatchShelf(shelfSubject, topics.first())
        }
    }

    private fun shelfTitle(gradeLevel: String, subject: String, taskType: String, paperIndex: Int): String =
        if (taskType == SHELF_PRACTICE_PAPER) {
            "$gradeLevel $subject Practice Paper $paperIndex"
        } else {
            "$gradeLevel $subject Study Guide"
        }

    private fun taskTypeLabel(taskType: String): String =
        taskType.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

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
        val effective = requested.ifEmpty { CONTENT_BATCH_DEFAULT_TASK_TYPES }
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

    private fun normalizedPracticePapers(count: Int): Int {
        if (count <= 0) throw invalidArgument("practicePapers must be a positive integer")
        return count.coerceAtMost(CONTENT_BATCH_MAX_PRACTICE_PAPERS)
    }

    private companion object {
        const val MAX_KEY_CHARS = 256
        const val SHELF_PRACTICE_PAPER = "PRACTICE_PAPER"
        const val SHELF_STUDY_GUIDE = "STUDY_GUIDE"
        const val PRACTICE_PAPER_SLUG = "practice-paper"
        const val STUDY_GUIDE_SLUG = "study-guide"
        val NON_SLUG = Regex("[^a-z0-9]")
        val NON_KEY_CHAR = Regex("[^a-z0-9:-]")
    }
}

/** One queued slot: a built provider request plus the topic it belongs to (null for a shelf item). */
private data class EnqueueSlot(val request: GenerationRequest, val topicId: UUID?)

/** One subject x grade shelf and the concept that anchors its curriculum mapping. */
private data class BatchShelf(val subject: String, val anchor: ConceptEntity)
