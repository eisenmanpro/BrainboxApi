package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Phase 7.5 supervisor: the bounded task loop around generation. It runs
 * generate -> validate -> revise up to app.content.loop.max-iterations, folding the
 * validator BLOCKER findings into the next request, and stops at a unit with no
 * blockers or when the accumulated provider cost reaches app.content.loop.max-cost-micros
 * (0 disables the ceiling).
 *
 * A unit still blocked when the budget is spent is returned unchanged; the
 * projection gate then hides it and writes it to the human exception queue, so an
 * unrevisable item escalates rather than publishing. The iteration count, cost and
 * last feedback are persisted on the job.
 *
 * With the default max-iterations of 1 the loop performs no revision and this is
 * exactly the previous single-shot worker path.
 */
@Service
class ContentTaskLoop(
    private val properties: AppContentProperties,
    private val router: ContentRouter,
    private val jobService: GenerationJobService,
    private val validation: ContentValidationService,
    private val jobs: GenerationJobRepository,
    private val units: ContentUnitRepository,
    private val agentRuns: AgentRunRepository,
    private val modelCalls: ModelCallRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(jobId: UUID): ContentUnitEntity? {
        val job = jobs.findById(jobId).orElse(null) ?: return null
        // A cache hit is completed idempotently by the router; nothing to revise.
        if (units.findByGenerationKey(job.generationKey) != null) {
            return router.runQueued(jobId)
        }

        var unit = router.runQueued(jobId) ?: return null
        val request = jobService.decode(job) ?: return unit
        val maxIterations = properties.loop.maxIterations.coerceAtLeast(1)
        var iterations = 1
        var feedback = job.loopFeedback

        while (iterations < maxIterations) {
            val report = validation.validate(CONTENT_TYPE_UNIT, unit.id)
            if (!report.blockers) break
            val ceiling = properties.loop.maxCostMicros
            val spent = costMicros(job.generationKey)
            if (ceiling > 0L && spent >= ceiling) {
                log.info("task loop for {} stopped at the cost ceiling after {} iteration(s)", job.generationKey, iterations)
                break
            }
            feedback = report.findings
                .filter { it.severity == FindingSeverity.BLOCKER }
                .joinToString("; ") { it.code + ": " + it.message }
                .take(MAX_FEEDBACK_CHARS)
            val revised = router.revise(job, request.copy(notes = mergeNotes(request.notes, feedback))) ?: break
            unit = revised
            iterations += 1
        }

        persistLoopState(job, iterations, costMicros(job.generationKey), feedback)
        return unit
    }

    private fun mergeNotes(existing: String?, feedback: String?): String {
        val revision = feedback?.takeIf { it.isNotBlank() } ?: return existing?.trim().orEmpty()
        val prompt = "Fix these problems from the previous attempt and return the whole corrected JSON: " + revision
        val base = existing?.trim().orEmpty()
        return if (base.isEmpty()) prompt else base + "\n" + prompt
    }

    private fun costMicros(generationKey: String): Long =
        agentRuns.findAllByGenerationKeyOrderByCreatedAtDesc(generationKey)
            .sumOf { run -> modelCalls.findAllByAgentRunId(run.id).sumOf { it.costMicros } }

    private fun persistLoopState(job: GenerationJobEntity, iterations: Int, costMicros: Long, feedback: String?) {
        jobs.findById(job.id).orElse(null)?.let { stored ->
            stored.loopIterations = iterations
            stored.loopCostMicros = costMicros
            stored.loopFeedback = feedback
            jobs.save(stored)
        }
    }

    private companion object {
        const val CONTENT_TYPE_UNIT = "UNIT"
        const val MAX_FEEDBACK_CHARS = 1000
    }
}
