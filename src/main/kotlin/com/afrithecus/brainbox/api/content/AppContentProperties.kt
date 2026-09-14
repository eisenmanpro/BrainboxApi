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
}
