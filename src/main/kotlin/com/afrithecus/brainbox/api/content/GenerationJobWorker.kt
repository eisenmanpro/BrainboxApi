package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

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
 */
@Component
class GenerationJobWorker(
    private val properties: AppContentProperties,
    private val service: GenerationJobService,
    private val router: ContentRouter,
    private val projection: ContentProjectionService,
    private val unitQuestions: ContentUnitQuestionRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.content.worker.poll-interval-ms:15000}")
    fun poll() {
        if (!properties.drainsJobs) return
        try {
            service.reclaimStale(properties.worker.staleRunSeconds)
            for (job in service.claim(properties.worker.batchSize)) {
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
        } catch (failure: Exception) {
            log.warn("generation worker poll failed: {}", failure.message)
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
