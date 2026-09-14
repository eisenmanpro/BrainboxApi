package com.afrithecus.brainbox.api.content.curriculum

import com.afrithecus.brainbox.api.content.AppContentProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Phase 7.5b-1: runs the deterministic Tier 0 curriculum seeder once on
 * startup, but only when [AppContentProperties.Curriculum.seedOnStartup] is
 * true. Default off so tests and production never seed implicitly. Disabled in
 * the test profile.
 */
@Component
class CurriculumSeedBootstrap(
    private val seeder: CurriculumSeeder,
    private val properties: AppContentProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!properties.curriculum.seedOnStartup) {
            log.debug("Tier 0 curriculum seeding is disabled (app.content.curriculum.seed-on-startup=false)")
            return
        }
        val summary = seeder.seed()
        log.info(
            "Tier 0 curriculum seed complete: version={} grades={} inserted={} updated={}",
            summary.version,
            summary.grades,
            summary.inserted,
            summary.updated,
        )
    }
}
