package com.afrithecus.brainbox.api.ops

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * O1 operations-backend settings. The rollup job writes the previous complete
 * hour by default and keeps a 90-day window, mirroring the audit-log retention.
 * [enabled] is false in the test profile so the scheduled job never races the
 * integration tests; tests drive [OpsRollupService] directly.
 */
@ConfigurationProperties(prefix = "app.ops")
data class AppOpsProperties(
    val rollup: Rollup = Rollup(),
) {

    data class Rollup(
        val enabled: Boolean = true,
        /** Interval between rollup runs. Default hourly. */
        val intervalMs: Long = 3_600_000,
        /** First run after startup, so the context is fully ready. */
        val initialDelayMs: Long = 300_000,
        /** Rollups older than this many days are purged. Default 90. */
        val retentionDays: Long = 90,
    )
}
