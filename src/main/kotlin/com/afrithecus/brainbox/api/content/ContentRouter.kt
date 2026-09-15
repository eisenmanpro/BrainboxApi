package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.VerificationAnswer
import com.afrithecus.brainbox.api.content.ai.VerificationQuestion
import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Cache-first content router (Phase 7.2, extended in 7.5a). It is the only caller
 * of [ContentGenerationProvider] and the only writer of the capture rows
 * (generation_jobs, agent_runs, model_calls): a cache hit returns the stored
 * content_units row without touching the provider, a miss enqueues a durable job
 * and runs the provider through the shared core.
 *
 * Phase 7.5f adds [verifyAnswerKeys]: a second, independent model interaction that
 * also runs through this class, so the provider/capture invariant holds for
 * verification too. Phase 7.5h gives that verification a per-question disposition:
 * when the policy allows it, a disputed question is dropped and the surviving keys
 * become the unit, all inside the same transaction as the capture.
 *
 * The run mode is not this class's concern. The synchronous [resolve] path runs
 * the core inline; the worker path claims a job and calls [runQueued]. Both paths
 * share one private core so provider capture and the unit/steps/questions
 * persistence can never diverge.
 */
@Service
class ContentRouter(
    private val contentUnits: ContentUnitRepository,
    private val unitSteps: ContentUnitStepRepository,
    private val unitQuestions: ContentUnitQuestionRepository,
    private val generationJobs: GenerationJobRepository,
    private val agentRuns: AgentRunRepository,
    private val modelCalls: ModelCallRepository,
    private val jobService: GenerationJobService,
    private val provider: ContentGenerationProvider,
    private val moderationPolicy: ModerationPolicyService,
    private val mapper: ObjectMapper,
    /** H1 pipeline metrics; the router is the only caller of the provider. */
    private val metrics: ContentMetrics,
) {

    /**
     * Synchronous cache-first resolve: hit returns the stored unit, miss enqueues
     * the job and runs the shared core inline on the caller's thread.
     */
    fun resolve(request: GenerationRequest): ContentUnitEntity {
        contentUnits.findByGenerationKey(request.generationKey)?.let { return it }

        val job = generationJobs.save(
            jobService.enqueue(request).apply {
                status = "RUNNING"
                attempts = attempts + 1
            }
        )
        val outcome = runCore(job, request)
        jobService.complete(job.id, outcome.runId)
        return outcome.unit
    }

    /**
     * Worker entry point for one claimed job. A cache hit completes the job
     * idempotently; an undecodable payload fails it; otherwise the shared core
     * runs and the job is completed on success. Exceptions propagate so the worker
     * can route them to [GenerationJobService.fail].
     */
    fun runQueued(jobId: UUID): ContentUnitEntity? {
        val job = generationJobs.findById(jobId).orElse(null) ?: return null

        contentUnits.findByGenerationKey(job.generationKey)?.let { existing ->
            jobService.complete(job.id, job.runId)
            return existing
        }

        val request = jobService.decode(job)
        if (request == null) {
            job.status = "FAILED"
            job.lastError = "generation request payload is missing or invalid"
            generationJobs.save(job)
            return null
        }

        val outcome = runCore(job, request)
        jobService.complete(job.id, outcome.runId)
        return outcome.unit
    }

    /**
     * Phase 7.5f independent answer-key verification. The router remains the only
     * caller of the provider and the only writer of the capture tables, so this is
     * a second model interaction through the same seam, not a second egress plane.
     *
     * It loads the unit and its questions; no questions means there is nothing to
     * verify and it returns null. An already-verified unit is reused unless [force]
     * is set, so re-projection (a human approval, an idempotent replay) never spends
     * another model call.
     *
     * The request deliberately carries only the question stem, type and options -
     * never the stored [ContentUnitQuestionEntity.correctAnswer] - so the second
     * model solves the question independently. Each returned answer is compared with
     * the stored key (normalised; see [answersMatch]). With the default policy
     * (`answer_key_drop_disagreements = true`) the disputed questions are deleted and
     * the surviving keys define the unit: agreement is 1.0 when at least one survives,
     * else 0.0. With the policy off, nothing is deleted and agreement stays
     * agreements / questions. Either way the unit records the timestamp and model, and
     * the deletion runs in this method's transaction so the capture and the
     * disposition cannot diverge. A provider failure is captured as a failed
     * model_call and rethrown: the unit stays unverified, which the auto-approval gate
     * treats as fail-closed.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun verifyAnswerKeys(unitId: UUID, force: Boolean = false): Double? {
        val unit = contentUnits.findById(unitId).orElse(null) ?: return null
        // Idempotency before the question load: an already-verified unit is reused
        // unless forced, even when a prior disposition dropped every question (the
        // stored agreement is then 0.0, not null).
        if (unit.answerKeyVerifiedAt != null && !force) return unit.answerKeyAgreement

        val questions = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unitId)
        if (questions.isEmpty()) return null

        val request = AnswerVerificationRequest(
            questions = questions.map { question ->
                VerificationQuestion(
                    orderIndex = question.orderIndex,
                    type = question.qType,
                    text = question.text,
                    options = parseOptions(question.options),
                )
            },
        )

        val run = agentRuns.save(
            AgentRunEntity().apply {
                generationKey = unit.generationKey
                promptVersion = ANSWER_VERIFY_PROMPT_VERSION
                status = "RUNNING"
            }
        )

        val startedAt = System.nanoTime()
        val result = try {
            provider.verifyAnswerKeys(request)
        } catch (failure: Exception) {
            metrics.recordProviderError(provider.name, ProviderErrorReasons.reasonFor(failure))
            recordFailedVerification(run, failure, startedAt)
            if (failure is ApiException) throw failure
            throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "answer-key verification failed for unit '" + unit.id + "': " +
                    (failure.message ?: failure.javaClass.simpleName),
            )
        }
        val latencyMs = elapsedMillis(startedAt)

        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                provider = this@ContentRouter.provider.name
                model = result.model
                promptTokens = result.promptTokens
                completionTokens = result.completionTokens
                costMicros = costMicros(result.promptTokens, result.completionTokens)
                this.latencyMs = latencyMs
                success = true
            }
        )

        val byIndex = result.answers.associateBy { it.orderIndex }
        val disputed = questions.filter { question ->
            !answersMatch(question, byIndex[question.orderIndex]?.answer)
        }

        val agreement = if (moderationPolicy.answerKeyDropDisagreements()) {
            // Per-question disposition (7.5h): drop exactly the disputed items, keep the
            // rest. Every survivor agrees by construction, so the unit is clean when at
            // least one question remains; an all-disputed unit records 0.0 and can never
            // clear the gate.
            if (disputed.isEmpty()) {
                unit.answerKeyDroppedDetail = null
            } else {
                unit.answerKeyDroppedDetail = droppedDetailJson(disputed, byIndex)
                unitQuestions.deleteAll(disputed)
            }
            unit.answerKeyDropped = disputed.size
            if (questions.size - disputed.size > 0) 1.0 else 0.0
        } else {
            // Legacy whole-unit behaviour: no deletion, agreement = agreements / questions.
            (questions.size - disputed.size).toDouble() / questions.size.toDouble()
        }

        unit.answerKeyAgreement = agreement
        unit.answerKeyVerifiedAt = Instant.now()
        unit.answerKeyVerifiedModel = result.model
        contentUnits.save(unit)

        run.status = "SUCCEEDED"
        run.model = result.model
        agentRuns.save(run)

        return agreement
    }

    /** Captures a failed verification call on the same capture tables as generation. */
    private fun recordFailedVerification(run: AgentRunEntity, failure: Exception, startedAt: Long) {
        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                provider = this@ContentRouter.provider.name
                this.latencyMs = elapsedMillis(startedAt)
                success = false
                error = failure.message?.take(MAX_ERROR_CHARS)
            }
        )
        run.status = "FAILED"
        agentRuns.save(run)
    }

    /**
     * JSON audit of the dropped questions, one object per item:
     * `{orderIndex, text, storedKey, verifiedAnswer}`. The stored key is captured
     * before deletion so the exception queue can explain what was removed; a missing
     * stored key or a dropped/blank independent answer is written as null.
     */
    private fun droppedDetailJson(
        disputed: List<ContentUnitQuestionEntity>,
        byIndex: Map<Int, VerificationAnswer>,
    ): String = mapper.writeValueAsString(
        disputed.map { question ->
            mapOf(
                "orderIndex" to question.orderIndex,
                "text" to question.text,
                "storedKey" to question.correctAnswer,
                "verifiedAnswer" to byIndex[question.orderIndex]?.answer,
            )
        },
    )

    /**
     * Forgiving but safe answer comparison.
     *
     * Both sides are normalised: trimmed, lowercased, internal whitespace collapsed
     * to a single space, and surrounding punctuation stripped
     * (. , ; : ! ? " ( ) [ ] { }). So "Two equal parts." agrees with "two  equal parts".
     *
     * For MULTIPLE_CHOICE only, the independent solver may also return an option
     * reference instead of the option text: a single letter (A/B/C/D,
     * case-insensitive) or a 1-based option number. Each reference is resolved to
     * the option at that position and compared with the stored key. The stored key
     * is never rewritten, and a reference outside the option list is compared as
     * plain text and cannot agree.
     */
    private fun answersMatch(question: ContentUnitQuestionEntity, given: String?): Boolean {
        val expected = question.correctAnswer?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val provided = given?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val candidates = if (question.qType.trim().uppercase() == MULTIPLE_CHOICE) {
            optionCandidates(provided, parseOptions(question.options).orEmpty())
        } else {
            listOf(provided)
        }
        val expectedNormalized = normalizeAnswer(expected)
        return candidates.any { normalizeAnswer(it) == expectedNormalized }
    }

    /** The literal answer plus, for MC, the option a letter or 1-based number points at. */
    private fun optionCandidates(given: String, options: List<String>): List<String> {
        val candidates = mutableListOf(given)
        val token = normalizeAnswer(given)
        if (token.length == 1 && token[0] in 'a'..'z') {
            options.getOrNull(token[0] - 'a')?.let { candidates += it }
        }
        token.toIntOrNull()?.let { number ->
            if (number in 1..options.size) candidates += options[number - 1]
        }
        return candidates
    }

    /** Case/space/punctuation-normalised text; punctuation is stripped only at the ends. */
    private fun normalizeAnswer(value: String): String =
        value.trim()
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim(*TRAILING_PUNCTUATION)
            .trim()

    private fun parseOptions(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val node = mapper.readTree(json)
            (0 until node.size()).map { node.get(it).asString() }
        }.getOrNull()
    }

    /**
     * The single shared execution core: provider call, model_calls capture,
     * agent_run lifecycle and content_units/steps/questions persistence. The
     * caller owns the terminal job status (complete/fail).
     */
    private fun runCore(job: GenerationJobEntity, request: GenerationRequest): RunOutcome {
        val providerName = provider.name
        var run = agentRuns.save(
            AgentRunEntity().apply {
                jobId = job.id
                generationKey = request.generationKey
                status = "RUNNING"
            }
        )

        val startedAt = System.nanoTime()
        val result = try {
            provider.generate(request)
        } catch (failure: Exception) {
            val latencyNanos = System.nanoTime() - startedAt
            metrics.recordGenerationLatency(providerName, latencyNanos)
            metrics.recordProviderError(providerName, ProviderErrorReasons.reasonFor(failure))
            modelCalls.save(
                ModelCallEntity().apply {
                    agentRunId = run.id
                    provider = providerName
                    this.latencyMs = latencyNanos / 1_000_000L
                    success = false
                    error = failure.message?.take(MAX_ERROR_CHARS)
                }
            )
            run.status = "FAILED"
            run.confidence = null
            agentRuns.save(run)
            job.status = "FAILED"
            job.runId = run.id
            job.lastError = failure.message?.take(MAX_ERROR_CHARS)
            generationJobs.save(job)
            if (failure is ApiException) throw failure
            throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "content generation failed for '" + request.generationKey + "': " + (failure.message ?: failure.javaClass.simpleName)
            )
        }
        val latencyNanos = System.nanoTime() - startedAt
        metrics.recordGenerationLatency(providerName, latencyNanos)
        metrics.recordTokens(result.promptTokens, result.completionTokens)
        val latencyMs = latencyNanos / 1_000_000L

        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                provider = providerName
                model = result.model
                promptTokens = result.promptTokens
                completionTokens = result.completionTokens
                costMicros = costMicros(result.promptTokens, result.completionTokens)
                this.latencyMs = latencyMs
                success = true
            }
        )

        val unit = contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = request.generationKey
                taskType = request.taskType
                // The provider returns teaching content, not a display title; derive the
                // title from the request so the structure validator can pass and the
                // projected post has a human-readable heading.
                title = request.conceptName?.takeIf { it.isNotBlank() }
                    ?: request.taskTypeLabel?.takeIf { it.isNotBlank() }
                    ?: request.taskType
                conceptId = job.conceptId
                subject = request.subject
                gradeLevel = request.gradeLevel
                language = request.language
                standardVersion = request.standardVersion
                promptVersion = run.promptVersion
                body = result.body
                provenance = "GENERATED"
                sourceUrls = result.sourceUrls.takeIf { it.isNotEmpty() }?.joinToString("\n")
                license = result.license
                model = result.model
                tokens = result.promptTokens + result.completionTokens
                confidence = result.confidence
                reviewState = "UNREVIEWED"
                status = "DRAFT"
            }
        )

        val savedSteps = result.steps.sortedBy { it.orderIndex }.map { generated ->
            unitSteps.save(
                ContentUnitStepEntity().apply {
                    unitId = unit.id
                    orderIndex = generated.orderIndex
                    title = generated.title
                    body = generated.body
                    figureSvg = generated.figureSvg
                }
            )
        }

        result.questions.sortedBy { it.orderIndex }.forEach { generated ->
            unitQuestions.save(
                ContentUnitQuestionEntity().apply {
                    unitId = unit.id
                    stepId = generated.stepIndex?.let { savedSteps.getOrNull(it)?.id }
                    orderIndex = generated.orderIndex
                    qType = generated.type
                    text = generated.text
                    options = generated.options?.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
                    correctAnswer = generated.correctAnswer
                    explanation = generated.explanation
                    points = generated.points
                    difficulty = generated.difficulty
                    matchingPairs = generated.matchingPairs?.takeIf { it.isNotEmpty() }
                        ?.let { mapper.writeValueAsString(it) }
                }
            )
        }

        run.status = "SUCCEEDED"
        run.model = result.model
        run.confidence = result.confidence
        agentRuns.save(run)

        return RunOutcome(unit, run.id)
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    /**
     * DeepSeek public list price, micros per million tokens (v3.2: ~$0.27 in /
     * ~$1.10 out). Stored as micros so cost is integer and currency-agnostic.
     */
    private fun costMicros(promptTokens: Int, completionTokens: Int): Long =
        (promptTokens.toLong() * PROMPT_MICROS_PER_MILLION + completionTokens.toLong() * COMPLETION_MICROS_PER_MILLION) /
            1_000_000L

    private data class RunOutcome(val unit: ContentUnitEntity, val runId: UUID)

    private companion object {
        const val MAX_ERROR_CHARS = 2000
        const val PROMPT_MICROS_PER_MILLION = 270_000L
        const val COMPLETION_MICROS_PER_MILLION = 1_100_000L

        /** Prompt-version marker that distinguishes a verification agent_run. */
        const val ANSWER_VERIFY_PROMPT_VERSION = "answer-verify-v1"
        const val MULTIPLE_CHOICE = "MULTIPLE_CHOICE"
        val WHITESPACE = Regex("\\s+")
        val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', '"', '(', ')', '[', ']', '{', '}')
    }
}
