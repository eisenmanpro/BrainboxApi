package com.afrithecus.brainbox.api.auth

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Daily prune of expired phone-verification challenges (mirrors the password-reset prune). */
@Component
class PhoneVerificationCleanupScheduler(private val service: PhoneVerificationService) {

    @Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    fun prune() {
        runCatching { service.pruneExpired() }
    }
}
