package com.afrithecus.brainbox.api.live

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Closes LIVE classes that were never ended (host closed the tab, lost the network and
 * never came back). Without this the hub keeps advertising a dead broadcast and the row
 * can never be scheduled again cleanly.
 */
@Component
class LiveClassExpiryScheduler(private val teacherLiveClassService: TeacherLiveClassService) {

    @Scheduled(initialDelay = 180_000, fixedDelay = 900_000)
    fun expireStaleSessions() {
        runCatching { teacherLiveClassService.expireStaleSessions() }
    }
}
