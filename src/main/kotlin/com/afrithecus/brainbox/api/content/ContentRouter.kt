package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.AnswerVerificationRequest
import com.afrithecus.brainbox.api.content.ai.ContentCritiqueRequest
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
import com.afrithecus.brainbox.api.content.ai.GenerationResult
import com.afrithecus.brainbox.api.content.ai.ProviderCallException
import com.afrithecus.brainbox.api.content.ai.VerificationAnswer
import com.afrithecus.brainbox.api.content.ai.VerificationQuestion
import com.afrithecus.brainbox.api.content.entity.AgentRunEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.entity.ModelCallEntity
import com.afrithecus.brainbox.api.content.figure.DiagramRenderer
import com.afrithecus.brainbox.api.content.figure.FigureSpecs
import com.afrithecus.brainbox.api.content.mcp.McpToolClient
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.schema.ContentSchemaV1
import com.afrithecus.brainbox.api.content.subject.SubjectAgentRegistry
import com.afrithecus.brainbox.api.learning.model.LearningScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
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
    /** Phase 7.5: resolves the subject-agent persona from the request subject. */
    private val subjectAgents: SubjectAgentRegistry,
    /** Phase 7.5: the in-process tool layer, used for curriculum grounding. */
    private val mcpTools: McpToolClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Synchronous cache-first resolve: hit returns the stored unit, miss enqueues
     * the job and runs the shared core inline on the caller's thread.
     */
    fun resolve(request: GenerationRequest): ContentUnitEntity {
        contentUnits.findByGenerationKey(request.generationKey)?.let {
            metrics.recordCacheHit()
            return it
        }
        metrics.recordCacheMiss()

        val job = generationJobs.save(
            jobService.enqueue(request, GenerationJobSource.USER).apply {
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
            metrics.recordCacheHit()
            jobService.complete(job.id, job.runId)
            return existing
        }
        metrics.recordCacheMiss()

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
                schoolId = unit.schoolId
                promptVersion = ANSWER_VERIFY_PROMPT_VERSION
                status = "RUNNING"
            }
        )

        val startedAt = System.nanoTime()
        val result = try {
            provider.verifyAnswerKeys(request)
        } catch (failure: Exception) {
            recordFailedCall(run, failure, System.nanoTime() - startedAt, recordLatency = false)
            run.status = "FAILED"
            agentRuns.save(run)
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
                provider = result.provider ?: this@ContentRouter.provider.name
                model = result.model
                promptTokens = result.promptTokens
                completionTokens = result.completionTokens
                costMicros = result.costMicros.takeIf { it > 0L }
                    ?: ContentPricing.costMicros(result.promptTokens, result.completionTokens)
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

    /**
     * Phase 7.5 LLM critic. A third model interaction through the same seam that
     * judges the unit's pedagogy (not safety) and stores the score, model and
     * structured findings on the unit. Reused unless [force], so re-projection never
     * spends another critic call. A provider failure is captured as a failed
     * model_call and rethrown; an uncritiqued unit stays null and the auto-approval
     * gate treats that as fail-closed when the critic is enabled.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun critique(unitId: UUID, force: Boolean = false): Double? {
        val unit = contentUnits.findById(unitId).orElse(null) ?: return null
        if (unit.critiqueAt != null && !force) return unit.critiqueScore

        val steps = unitSteps.findAllByUnitIdOrderByOrderIndexAsc(unitId)
        val questions = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unitId)
        val request = ContentCritiqueRequest(
            taskType = unit.taskType,
            subject = unit.subject,
            gradeLevel = unit.gradeLevel,
            title = unit.title,
            body = unit.body,
            steps = steps.mapNotNull { it.body?.takeIf { body -> body.isNotBlank() } },
            questions = questions.map { it.text },
        )

        val run = agentRuns.save(
            AgentRunEntity().apply {
                generationKey = unit.generationKey
                schoolId = unit.schoolId
                promptVersion = CRITIQUE_PROMPT_VERSION
                status = "RUNNING"
            }
        )
        val startedAt = System.nanoTime()
        val result = try {
            provider.critique(request)
        } catch (failure: Exception) {
            metrics.recordProviderError(provider.name, ProviderErrorReasons.reasonFor(failure))
            recordFailedCall(run, failure, System.nanoTime() - startedAt, recordLatency = false)
            run.status = "FAILED"
            agentRuns.save(run)
            if (failure is ApiException) throw failure
            throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "pedagogy critique failed for unit '" + unit.id + "': " + (failure.message ?: failure.javaClass.simpleName),
            )
        }

        val servedProvider = result.provider ?: provider.name
        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                provider = servedProvider
                model = result.model
                promptTokens = result.promptTokens
                completionTokens = result.completionTokens
                costMicros = result.costMicros.takeIf { it > 0L }
                    ?: ContentPricing.costMicros(result.promptTokens, result.completionTokens)
                latencyMs = elapsedMillis(startedAt)
                success = true
            }
        )

        unit.critiqueScore = result.score
        unit.critiqueAt = Instant.now()
        unit.critiqueModel = result.model
        unit.critiqueFindings = result.findings.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
        contentUnits.save(unit)

        run.status = "SUCCEEDED"
        run.model = result.model
        agentRuns.save(run)
        return result.score
    }

    /**
     * Records a failed provider interaction on the capture tables. A routed call
     * carries one attempt per provider it tried, so each failed candidate gets its
     * own model_call and error meter rather than collapsing to the router. A direct
     * provider (tests, the disabled seam) has no attempt list and is captured once.
     */
    private fun recordFailedCall(run: AgentRunEntity, failure: Exception, totalNanos: Long, recordLatency: Boolean) {
        for ((providerName, latencyMs, message) in failedAttempts(failure, totalNanos)) {
            if (recordLatency) metrics.recordGenerationLatency(providerName, latencyMs * 1_000_000L)
            metrics.recordProviderError(providerName, ProviderErrorReasons.reasonForMessage(message))
            modelCalls.save(failedCall(run, providerName, latencyMs, message))
        }
    }

    /** The failed attempts of a routed call, or a single attempt for a direct provider. */
    private fun failedAttempts(failure: Exception, totalNanos: Long): List<Triple<String, Long, String?>> {
        val routed = failure as? ProviderCallException
        if (routed != null && routed.attempts.isNotEmpty()) {
            return routed.attempts.map { Triple(it.provider, it.latencyMs, it.error) }
        }
        return listOf(Triple(provider.name, totalNanos / 1_000_000L, failure.message))
    }

    private fun failedCall(run: AgentRunEntity, providerName: String, latencyMs: Long, message: String?): ModelCallEntity =
        ModelCallEntity().apply {
            agentRunId = run.id
            provider = providerName
            this.latencyMs = latencyMs
            success = false
            error = message?.take(MAX_ERROR_CHARS)
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
        val run = beginRun(job, request.generationKey)
        val result = generateCaptured(run, job, request)
        val unit = ContentUnitEntity()
        applyGeneratedFields(unit, request, job, run, result)
        contentUnits.save(unit)
        persistStepsAndQuestions(unit, result)
        finishRun(run, result)
        return RunOutcome(unit, run.id)
    }

    /**
     * Phase 7.5 supervisor: regenerate an existing unit in place from a revised
     * request (the validator findings folded into GenerationRequest.notes). It
     * writes a new agent_run/model_call so the extra cost is captured like any
     * other call, replaces the body/steps/questions, and clears the review and
     * answer-key verification state because the content is new. Returns null when
     * there is no unit to revise.
     */
    fun revise(job: GenerationJobEntity, request: GenerationRequest): ContentUnitEntity? {
        val unit = contentUnits.findByGenerationKey(request.generationKey) ?: return null
        val run = beginRun(job, request.generationKey)
        val result = generateCaptured(run, job, request)
        unitSteps.deleteAll(unitSteps.findAllByUnitIdOrderByOrderIndexAsc(unit.id))
        unitQuestions.deleteAll(unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unit.id))
        applyGeneratedFields(unit, request, job, run, result)
        contentUnits.save(unit)
        persistStepsAndQuestions(unit, result)
        finishRun(run, result)
        return unit
    }

    /**
     * Resolve the concept's curriculum grounding through the concept_lookup tool
     * (strand, sub-strand, learning outcome) so the prompt is anchored in the seeded
     * CBC catalogue. A lookup failure is logged and ignored: grounding is an
     * enrichment, never a reason to lose a generation.
     */
    private fun curriculumContext(request: GenerationRequest): String? {
        val code = request.conceptCode?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val payload = mapper.createObjectNode().put("code", code).put("gradeLevel", request.gradeLevel)
            val node = mcpTools.invoke("concept_lookup", payload)
            val parts = buildList {
                node.get("strandName")?.asString()?.takeIf { it.isNotBlank() }?.let { add("strand: " + it) }
                node.get("substrandName")?.asString()?.takeIf { it.isNotBlank() }?.let { add("sub-strand: " + it) }
                node.get("learningOutcome")?.asString()?.takeIf { it.isNotBlank() }?.let { add("learning outcome: " + it) }
            }
            parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
        } catch (failure: Exception) {
            log.warn("concept_lookup grounding failed for {}: {}", code, failure.message)
            null
        }
    }

    private fun beginRun(job: GenerationJobEntity, generationKey: String): AgentRunEntity =
        agentRuns.save(
            AgentRunEntity().apply {
                jobId = job.id
                this.generationKey = generationKey
                schoolId = job.schoolId
                status = "RUNNING"
            }
        )

    /** One provider call with its capture and fail-closed job routing. Throws on failure. */
    private fun generateCaptured(
        run: AgentRunEntity,
        job: GenerationJobEntity,
        request: GenerationRequest,
    ): GenerationResult {
        val providerName = provider.name
        // Resolve the subject-agent persona here so every generation path (initial
        // and revise) is grounded in the subject, without the provider needing to
        // know about the agent registry.
        val enriched = request.copy(
            persona = subjectAgents.forSubject(request.subject).persona,
            curriculumContext = request.curriculumContext ?: curriculumContext(request),
        )
        val startedAt = System.nanoTime()
        val result = try {
            provider.generate(enriched)
        } catch (failure: Exception) {
            recordFailedCall(run, failure, System.nanoTime() - startedAt, recordLatency = true)
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
        // Attribute the call to the provider that actually served it (a routed call
        // may have failed over), so cost, latency and errors stay per-vendor.
        val servedProvider = result.provider ?: providerName
        metrics.recordGenerationLatency(servedProvider, latencyNanos)
        metrics.recordTokens(result.promptTokens, result.completionTokens)
        modelCalls.save(
            ModelCallEntity().apply {
                agentRunId = run.id
                provider = servedProvider
                model = result.model
                promptTokens = result.promptTokens
                completionTokens = result.completionTokens
                costMicros = result.costMicros.takeIf { it > 0L }
                    ?: ContentPricing.costMicros(result.promptTokens, result.completionTokens)
                latencyMs = latencyNanos / 1_000_000L
                success = true
            }
        )
        return result
    }

    /** Applies generated fields to a new or existing unit. */
    private fun applyGeneratedFields(
        unit: ContentUnitEntity,
        request: GenerationRequest,
        job: GenerationJobEntity,
        run: AgentRunEntity,
        result: GenerationResult,
    ) {
        unit.generationKey = request.generationKey
        // Phase 7.5 scope inheritance: the request selects the scope, but a
        // non-global scope needs the job's server-derived tenant, so an unscoped
        // job falls back to GLOBAL rather than writing a school row with no school.
        val requestedScope = runCatching {
            LearningScope.valueOf((request.scope ?: "GLOBAL").trim().uppercase())
        }.getOrDefault(LearningScope.GLOBAL)
        val effectiveScope = if (requestedScope == LearningScope.GLOBAL || job.schoolId == null) {
            LearningScope.GLOBAL
        } else {
            requestedScope
        }
        unit.scope = effectiveScope
        unit.schoolId = if (effectiveScope == LearningScope.GLOBAL) null else job.schoolId
        // Provenance: which content JSON contract this unit was generated under.
        unit.contentSchemaVersion = ContentSchemaV1.VERSION
        unit.taskType = request.taskType
        // The provider returns teaching content, not a display title; derive the
        // title from the request so the structure validator can pass and the
        // projected post has a human-readable heading.
        unit.title = request.conceptName?.takeIf { it.isNotBlank() }
            ?: request.taskTypeLabel?.takeIf { it.isNotBlank() }
            ?: request.taskType
        unit.conceptId = job.conceptId
        unit.subject = request.subject
        unit.gradeLevel = request.gradeLevel
        unit.language = request.language
        unit.standardVersion = request.standardVersion
        unit.promptVersion = run.promptVersion
        unit.body = result.body
        unit.provenance = "GENERATED"
        unit.sourceUrls = result.sourceUrls.takeIf { it.isNotEmpty() }?.joinToString("\n")
        unit.license = result.license
        unit.model = result.model
        unit.tokens = result.promptTokens + result.completionTokens
        unit.confidence = result.confidence
        unit.reviewState = "UNREVIEWED"
        unit.status = "DRAFT"
        // New content invalidates the prior verification and gate reason.
        unit.answerKeyAgreement = null
        unit.answerKeyVerifiedAt = null
        unit.answerKeyVerifiedModel = null
        unit.answerKeyDropped = 0
        unit.answerKeyDroppedDetail = null
        unit.autoApproveBlockedReason = null
        // New content invalidates any prior pedagogy critique too.
        unit.critiqueScore = null
        unit.critiqueAt = null
        unit.critiqueModel = null
        unit.critiqueFindings = null
    }

    private fun persistStepsAndQuestions(unit: ContentUnitEntity, result: GenerationResult) {
        val savedSteps = result.steps.sortedBy { it.orderIndex }.map { generated ->
            unitSteps.save(
                ContentUnitStepEntity().apply {
                    unitId = unit.id
                    orderIndex = generated.orderIndex
                    title = generated.title
                    body = generated.body
                    figureSpec = generated.figure?.let { mapper.writeValueAsString(it) }
                    figureSvg = generated.figure?.let { renderFigure(it) }
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
                    figureSpec = generated.figure?.let { mapper.writeValueAsString(it) }
                    figureSvg = generated.figure?.let { renderFigure(it) }
                }
            )
        }
    }

    /**
     * Renders a model-authored figure spec to SVG. The spec already passed the
     * closed-vocabulary check in the provider, so this only builds elements; any
     * model text is escaped inside the renderer.
     */
    private fun renderFigure(figure: JsonNode): String = DiagramRenderer.render(FigureSpecs.parse(figure))

    private fun finishRun(run: AgentRunEntity, result: GenerationResult) {
        run.status = "SUCCEEDED"
        run.model = result.model
        run.confidence = result.confidence
        agentRuns.save(run)
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private data class RunOutcome(val unit: ContentUnitEntity, val runId: UUID)

    private companion object {
        const val MAX_ERROR_CHARS = 2000

        /** Prompt-version marker that distinguishes a verification agent_run. */
        const val ANSWER_VERIFY_PROMPT_VERSION = "answer-verify-v1"
        const val CRITIQUE_PROMPT_VERSION = "pedagogy-critique-v1"
        const val MULTIPLE_CHOICE = "MULTIPLE_CHOICE"
        val WHITESPACE = Regex("\\s+")
        val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', '"', '(', ')', '[', ']', '{', '}')
    }
}
