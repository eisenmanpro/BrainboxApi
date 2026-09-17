package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.AgentRunRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.repository.ModelCallRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.content.validation.FindingSeverity
import com.afrithecus.brainbox.api.content.validation.ValidationReport
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

        while (true) {
            // Evaluate the current unit first, so the final generation is always
            // validated (and critiqued) even when the revision budget is then spent.
            val report = validation.validate(CONTENT_TYPE_UNIT, unit.id)
            // The LLM critic is an optional quality gate (Phase 7.5): when enabled, a
            // missing or below-bar pedagogy score is a revision trigger exactly like a
            // validator blocker, and its findings become the revision feedback.
            val critique = if (properties.critique.enabled) router.critique(unit.id) else null
            val critiqueLow = properties.critique.enabled &&
                (critique == null || critique < properties.critique.minScore)
            if (!report.blockers && !critiqueLow) break
            if (iterations >= maxIterations) break
            val ceiling = properties.loop.maxCostMicros
            val spent = costMicros(job.generationKey)
            if (ceiling > 0L && spent >= ceiling) {
                log.info("task loop for {} stopped at the cost ceiling after {} iteration(s)", job.generationKey, iterations)
                break
            }
            feedback = feedbackFor(report, unit, critiqueLow)
            val revised = router.revise(job, request.copy(notes = mergeNotes(request.notes, feedback))) ?: break
            unit = revised
            iterations += 1
        }

        persistLoopState(job, iterations, costMicros(job.generationKey), feedback)
        return unit
    }

    /** The validator blockers plus, when the critic tripped, its findings. */
    private fun feedbackFor(report: ValidationReport, unit: ContentUnitEntity, critiqueLow: Boolean): String {
        val blockers = report.findings
            .filter { it.severity == FindingSeverity.BLOCKER }
            .joinToString("; ") { it.code + ": " + it.message }
        val critique = if (critiqueLow) {
            val score = unit.critiqueScore?.let { "score " + it } ?: "no score"
            val detail = unit.critiqueFindings?.takeIf { it.isNotBlank() }?.let { ": " + it }
            "pedagogy critic (" + score + ")" + (detail ?: "")
        } else {
            null
        }
        return listOfNotNull(blockers.takeIf { it.isNotBlank() }, critique)
            .joinToString(" | ")
            .take(MAX_FEEDBACK_CHARS)
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
