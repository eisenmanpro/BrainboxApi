package com.afrithecus.brainbox.api.common.config

import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Minimal, non-sensitive /actuator/info payload for deployment dashboards.
 * Build/env details stay out; only the app name and process start time.
 */
@Component
class AppInfoContributor : InfoContributor {

    private val startedAt: Instant = Instant.now()

    override fun contribute(builder: Info.Builder) {
        builder.withDetail(
            "app",
            mapOf(
                "name" to "BrainboxApi",
                "startedAt" to startedAt.toString(),
            ),
        )
    }
}
