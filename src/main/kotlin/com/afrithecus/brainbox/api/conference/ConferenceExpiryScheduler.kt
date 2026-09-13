package com.afrithecus.brainbox.api.conference

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Periodically expires stale conference booking requests (api_conference_changes.md §7). */
@Component
class ConferenceExpiryScheduler(private val conferenceService: ConferenceService) {

    @Scheduled(initialDelay = 120_000, fixedDelay = 1_800_000)
    fun expireStaleRequests() {
        runCatching { conferenceService.expireStaleRequests() }
    }
}
