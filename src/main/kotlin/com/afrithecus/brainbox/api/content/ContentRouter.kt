package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.ai.GenerationRequest
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
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Cache-first content router (Phase 7.2, extended in 7.5a). It is the only caller
 * of [ContentGenerationProvider] and the only writer of the capture rows
 * (generation_jobs, agent_runs, model_calls): a cache hit returns the stored
 * content_units row without touching the provider, a miss enqueues a durable job
 * and runs the provider through the shared core.
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
    private val mapper: ObjectMapper,
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
     * The single shared execution core: provider call, model_calls capture,
     * agent_run lifecycle and content_units/steps/questions persistence. The
     * caller owns the terminal job status (complete/fail).
     */
    private fun runCore(job: GenerationJobEntity, request: GenerationRequest): RunOutcome {
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
            val latencyMs = elapsedMillis(startedAt)
            modelCalls.save(
                ModelCallEntity().apply {
                    agentRunId = run.id
                    provider = this@ContentRouter.provider.name
                    this.latencyMs = latencyMs
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
    }
}
