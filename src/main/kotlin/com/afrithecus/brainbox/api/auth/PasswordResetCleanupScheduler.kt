package com.afrithecus.brainbox.api.auth

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Daily prune of expired password-reset challenges. */
@Component
class PasswordResetCleanupScheduler(private val passwordResetService: PasswordResetService) {

    @Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    fun prune() {
        runCatching { passwordResetService.pruneExpired() }
    }
}
