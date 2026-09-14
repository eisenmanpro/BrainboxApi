package com.afrithecus.brainbox.api.content

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

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
 */
@Component
class GenerationJobWorker(
    private val properties: AppContentProperties,
    private val service: GenerationJobService,
    private val router: ContentRouter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.content.worker.poll-interval-ms:15000}")
    fun poll() {
        if (!properties.drainsJobs) return
        try {
            service.reclaimStale(properties.worker.staleRunSeconds)
            for (job in service.claim(properties.worker.batchSize)) {
                try {
                    router.runQueued(job.id)
                } catch (failure: Exception) {
                    service.fail(job.id, failure.message ?: failure.javaClass.simpleName)
                }
            }
        } catch (failure: Exception) {
            log.warn("generation worker poll failed: {}", failure.message)
        }
    }
}
