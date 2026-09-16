package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.entity.GenerationJobEntity
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ExecutionException

/**
 * Phase 7.5a generation worker. Drains the durable generation_jobs queue on a
 * fixed delay: it reclaims jobs whose RUNNING row went stale after a crash,
 * claims a batch of eligible QUEUED jobs and runs each through
 * [ContentRouter.runQueued].
 *
 * Run mode is owned here, not by the router. With app.content.run-mode=api this
 * method returns immediately and the JVM enqueues only, so a separate worker (or
 * a worker/both JVM) drains; with both the same JVM enqueues and drains.
 *
 * Any failure is routed to [GenerationJobService.fail] and swallowed: the
 * scheduled method must never throw or it would kill the poller.
 *
 * Phase 7.5c makes the worker the point where machine-first approval becomes real:
 * after a job produces a unit, [ContentProjectionService.project] runs the gate and
 * either auto-approves-and-publishes the unit or writes it hidden for the exception
 * queue. A projection failure is handled exactly like a generation failure.
 *
 * Phase 7.5f adds the independent answer-key solve between generation and
 * projection for any unit that has questions. A verification failure is caught and
 * logged so the poller is never killed and the job is never lost; the unit simply
 * stays unverified and the assessment auto-approval gate refuses it. Re-projection
 * (a human approval) calls [ContentRouter.verifyAnswerKeys] with force=false, so
 * the stored verification is reused and no second model call is made.
 *
 * H4 (concurrency) bounds the drain per JVM. The claim still runs first and marks
 * every selected row RUNNING before any processing, so selection keeps the H2
 * pause/source/USER-first semantics and no two workers can take the same job. Only
 * execution is parallelised: the claimed batch is submitted to a fixed executor of
 * [AppContentProperties.Worker.concurrency] threads and the poller waits for the
 * whole batch before returning, so a fixed-delay pass stays bounded. Each job
 * keeps its own try/catch, so one failure cannot stop the batch. At the default
 * concurrency of 1 the batch runs inline on the poller thread, which is byte for
 * byte the phase 7.5a behaviour and preserves the ambient-transaction semantics of
 * the integration tests.
 */
@Component
class GenerationJobWorker(
    private val properties: AppContentProperties,
    private val service: GenerationJobService,
    private val router: ContentRouter,
    private val projection: ContentProjectionService,
    private val unitQuestions: ContentUnitQuestionRepository,
    /** H2 runtime policy: pause and source filter, overridable without a redeploy. */
    private val moderationPolicy: ModerationPolicyService,
    /** H4: the single bounded pool for this JVM; see [ContentWorkerExecutorConfig]. */
    @Qualifier("generationWorkerExecutor") private val executor: ThreadPoolTaskExecutor,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.content.worker.poll-interval-ms:15000}")
    fun poll() {
        if (!properties.drainsJobs) return
        // H2: pausing is a runtime policy decision. When paused the poller does
        // nothing at all (no reclaim, no claim), so queued work is untouched and
        // resumes exactly where it left off once the policy is cleared.
        if (moderationPolicy.contentWorkerPaused(properties.worker.paused)) return
        val sources = moderationPolicy.contentWorkerSources(properties.worker.sources)
        if (sources.isEmpty()) return
        try {
            service.reclaimStale(properties.worker.staleRunSeconds)
            processBatch(service.claim(properties.worker.batchSize, sources))
        } catch (failure: Exception) {
            log.warn("generation worker poll failed: {}", failure.message)
        }
    }

    /**
     * H4: run the already-claimed batch. Claiming selected and marked the rows
     * RUNNING, so this method only parallelises execution and never changes which
     * jobs were chosen or their order. It waits for every job before returning so
     * the fixed-delay poller cannot pile up overlapping passes.
     */
    private fun processBatch(claimed: List<GenerationJobEntity>) {
        if (claimed.isEmpty()) return
        val concurrency = properties.worker.concurrency.coerceAtLeast(1)
        if (concurrency == 1) {
            // No thread hop at concurrency 1: this keeps the original single-thread
            // transaction semantics and gains nothing anyway.
            claimed.forEach { processOne(it) }
            return
        }
        val futures = claimed.map { job -> executor.submit(Runnable { processOne(job) }) }
        for (future in futures) {
            try {
                future.get()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("generation worker interrupted while waiting for the batch")
                return
            } catch (failed: ExecutionException) {
                // processOne routes its own failures; this guard keeps one freak
                // task failure from skipping the wait for the rest of the batch.
                log.warn("generation worker task ended unexpectedly: {}", failed.cause?.message)
            }
        }
    }

    /**
     * One claimed job with its own terminal routing. Generation, verification and
     * projection share one thread for the job; a failure at any point is recorded
     * with [GenerationJobService.fail] and swallowed so the rest of the batch runs.
     */
    private fun processOne(job: GenerationJobEntity) {
        try {
            // Projecting is what makes a generated unit real: a clean unit is
            // auto-approved and published here, anything that fails a gate is
            // written hidden (DRAFT/isPublished=false) for the review queue.
            val unit = router.runQueued(job.id)
            if (unit != null) {
                verifyAnswerKeys(unit.id)
                projection.project(unit.id)
            }
        } catch (failure: Exception) {
            service.fail(job.id, failure.message ?: failure.javaClass.simpleName)
        }
    }

    /**
     * Phase 7.5f: verify the unit's answer keys before projection. Runs only when
     * the unit has questions. A verification failure must not crash the poller or
     * lose the job, so it is logged and swallowed: the unit stays unverified and
     * projection still runs, writing it hidden for the exception queue.
     */
    private fun verifyAnswerKeys(unitId: UUID) {
        if (unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unitId).isEmpty()) return
        try {
            router.verifyAnswerKeys(unitId)
        } catch (failure: Exception) {
            log.warn("answer-key verification failed for unit {}: {}", unitId, failure.message)
        }
    }
}
