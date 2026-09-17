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
    val tools: Tools = Tools(),
    val loop: Loop = Loop(),
) {

    /** True when this JVM should execute queued generation work. */
    val drainsJobs: Boolean get() = runMode != RunMode.API

    enum class RunMode { API, WORKER, BOTH }

    data class Worker(
        val batchSize: Int = 5,
        /**
         * H4: maximum number of claimed jobs processed in parallel inside this JVM.
         * Default 1 reproduces the phase 7.5a sequential pass exactly. A value above
         * 1 hands the claimed batch to a fixed executor of this size and the poller
         * waits for the whole batch before returning, so the fixed-delay schedule
         * stays bounded and passes cannot pile up.
         *
         * The shared limits are the model provider (its sustained request rate and
         * quota) and the database connection pool. Keep
         * concurrency x worker-instances below the pool size and at or under the
         * provider's sustained rate. A sensible single-instance start is 4, with
         * batch-size at least concurrency; multiple worker instances are already
         * safe because a claim is an optimistic update on the entity version.
         */
        val concurrency: Int = 1,
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

    /**
     * Phase 7.5 supervisor task loop. The loop runs generate -> validate -> revise
     * up to [maxIterations], stopping at a unit with no BLOCKER findings or when
     * the accumulated provider cost reaches [maxCostMicros] (0 disables the cost
     * ceiling). maxIterations = 1 is the previous single-shot behaviour.
     */
    data class Loop(
        val maxIterations: Int = 1,
        val maxCostMicros: Long = 0,
    )

    /**
     * Phase 7.5 MCP tool settings. The tools are in-process beans behind
     * [com.afrithecus.brainbox.api.content.mcp.McpToolClient]; only the web tool
     * needs configuration and it is off by default.
     */
    data class Tools(
        val webFetch: WebFetch = WebFetch(),
    )

    /**
     * Grounded web intel (docs/PHASE7_AGENT_ARCHITECTURE.md 2.9). Scraped material
     * is grounding only: allow-list sources, record the URL, never reproduce the
     * text verbatim in learner content. Off by default; an empty allow-list denies
     * every host even when enabled.
     */
    data class WebFetch(
        val enabled: Boolean = false,
        /** Host suffixes permitted; empty means deny all. */
        val allowedDomains: List<String> = emptyList(),
        /** Host suffixes always blocked (exam/curriculum bodies we must not scrape). */
        val blockedDomains: List<String> = listOf("kicd.ac.ke", "knec.ac.ke"),
        val maxBytes: Int = 1_000_000,
        val timeoutMs: Long = 5000,
    )
}
