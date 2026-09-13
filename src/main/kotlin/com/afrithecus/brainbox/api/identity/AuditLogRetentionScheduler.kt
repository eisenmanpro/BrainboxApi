package com.afrithecus.brainbox.api.identity

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Daily prune of audit entries past the documented retention window. */
@Component
class AuditLogRetentionScheduler(private val auditLogService: AuditLogService) {

    @Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    fun prune() {
        runCatching { auditLogService.prune() }
    }
}
