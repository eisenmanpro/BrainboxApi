package com.afrithecus.brainbox.api.content

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Phase 7.5a generation run-mode and worker settings.
 *
 * The router never reads this: the run mode is owned by the worker so the API
 * process can stay a pure enqueue-and-serve plane while a separate worker JVM
 * (or the same JVM in `both`) drains the durable queue.
 *
 * - `API`: enqueue only; a separate worker deployment drains.
 * - `WORKER`: drain only; the HTTP surface may still be disabled by a fleet.
 * - `BOTH` (default): enqueue and drain in this JVM.
 */
@ConfigurationProperties(prefix = "app.content")
data class AppContentProperties(
    val runMode: RunMode = RunMode.BOTH,
    val worker: Worker = Worker(),
    val curriculum: Curriculum = Curriculum(),
    val batch: Batch = Batch(),
) {

    /** True when this JVM should execute queued generation work. */
    val drainsJobs: Boolean get() = runMode != RunMode.API

    enum class RunMode { API, WORKER, BOTH }

    data class Worker(
        val batchSize: Int = 5,
        val maxAttempts: Int = 5,
        val staleRunSeconds: Long = 900,
        val pollIntervalMs: Long = 15000,
        val retryBackoffSeconds: Long = 60,
        /**
         * H2 static default: when true the worker claims nothing, so autonomous
         * generation pauses without a redeploy. The runtime override is the
         * `content_worker_paused` policy key (see
         * [ModerationPolicyService.contentWorkerPaused]); this value is the
         * fallback when that key is absent or malformed.
         */
        val paused: Boolean = false,
        /**
         * H2 static default: which job sources the worker may claim. The runtime
         * override is the `content_worker_sources` policy key (a JSON array of
         * strings; see [ModerationPolicyService.contentWorkerSources]); this value
         * is the fallback when that key is absent or malformed.
         */
        val sources: List<String> = GenerationJobSource.ALL,
    )

    /**
     * Phase 7.5b-1 Tier 0 curriculum seeding. When [seedOnStartup] is true the
     * CurriculumSeedBootstrap runs the deterministic catalogue seeder once the
     * application is ready. Off by default so tests and production never seed
     * implicitly.
     */
    data class Curriculum(
        val seedOnStartup: Boolean = false,
    )

    /**
     * Phase 7.5e Tier 1 batch producer. When [runOnStartup] is true the
     * ApplicationReadyEvent bootstrap enqueues one generation job per Tier 0 topic
     * x task type through the durable queue; the worker drains it. Default off so
     * tests and production never enqueue implicitly. The same settings describe the
     * admin batch call's defaults.
     */
    data class Batch(
        val runOnStartup: Boolean = false,
        val gradeLevel: String = "Grade 4",
        val subject: String? = null,
        val taskTypes: List<String> = listOf("NOTES", "QUIZ"),
        val language: String = "en",
        val standardVersion: String = "v1",
        val limit: Int = 50,
    )
}
