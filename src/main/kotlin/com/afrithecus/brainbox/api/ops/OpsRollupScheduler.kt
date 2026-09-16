package com.afrithecus.brainbox.api.ops

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * O1 schedules. The rollup runs once an hour (configurable) and writes the
 * previous complete hour; retention mirrors the audit-log prune and runs daily.
 * The same guarded pattern as [com.afrithecus.brainbox.api.identity.AuditLogRetentionScheduler]
 * is used: a scheduling failure must never take the application down.
 * [AppOpsProperties.Rollup.enabled] is false in the test profile.
 */
@Component
class OpsRollupScheduler(
    private val service: OpsRollupService,
    private val properties: AppOpsProperties,
) {

    @Scheduled(
        initialDelayString = "\${app.ops.rollup.initial-delay-ms:300000}",
        fixedDelayString = "\${app.ops.rollup.interval-ms:3600000}",
    )
    fun rollup() {
        if (!properties.rollup.enabled) return
        runCatching { service.rollupPreviousHour() }
    }

    @Scheduled(initialDelay = 600_000, fixedDelay = 86_400_000)
    fun purge() {
        if (!properties.rollup.enabled) return
        runCatching { service.purgeOldRollups() }
    }
}
