package com.afrithecus.brainbox.api.content.batch

import com.afrithecus.brainbox.api.content.AppContentProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Phase 7.5e one-command path: on ApplicationReadyEvent, when
 * `app.content.batch.run-on-startup=true`, enqueue the Tier 1 batch described by
 * the `app.content.batch.*` settings and log the summary. Default off so tests and
 * production never enqueue implicitly.
 *
 * Ordered after [com.afrithecus.brainbox.api.content.curriculum.CurriculumSeedBootstrap]
 * so seeding and producing can be enabled together on a fresh environment.
 *
 * One-shot run (env vars, all optional except the two booleans):
 * `CONTENT_CURRICULUM_SEED_ON_STARTUP=true CONTENT_BATCH_RUN_ON_STARTUP=true
 * CONTENT_BATCH_GRADE_LEVEL="Grade 4" CONTENT_BATCH_SUBJECT=Mathematics
 * CONTENT_BATCH_TASK_TYPES=NOTES,QUIZ CONTENT_BATCH_LANGUAGE=en
 * CONTENT_BATCH_STANDARD_VERSION=v1 CONTENT_BATCH_LIMIT=50`.
 * The same run can be triggered without a restart via `POST /admin/content/batch`.
 */
@Component
class ContentBatchBootstrap(
    private val batch: ContentBatchService,
    private val properties: AppContentProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Order(ORDER)
    fun onApplicationReady() {
        val settings = properties.batch
        if (!settings.runOnStartup) {
            log.debug("Tier 1 batch producer is disabled (app.content.batch.run-on-startup=false)")
            return
        }
        val summary = batch.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = settings.gradeLevel,
                subject = settings.subject,
                taskTypes = settings.taskTypes,
                language = settings.language,
                standardVersion = settings.standardVersion,
                limit = settings.limit,
            )
        )
        log.info(
            "Tier 1 batch startup run complete: subject={} gradeLevel={} taskTypes={} conceptsMatched={} conceptsQueued={} jobsEnqueued={} jobsAlreadyPresent={} truncated={}",
            summary.subject,
            summary.gradeLevel,
            summary.taskTypes,
            summary.conceptsMatched,
            summary.conceptsQueued,
            summary.jobsEnqueued,
            summary.jobsAlreadyPresent,
            summary.truncated,
        )
    }

    companion object {
        /** Runs after the curriculum seeder (order 0) on the same event. */
        const val ORDER = 1
    }
}
