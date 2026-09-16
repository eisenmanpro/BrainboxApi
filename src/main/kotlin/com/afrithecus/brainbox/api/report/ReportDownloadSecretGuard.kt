package com.afrithecus.brainbox.api.report

import jakarta.annotation.PostConstruct
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to start with the placeholder download secret outside the test profile.
 * The HMAC key is the only authorization on `/teacher/reports/download/` (the
 * endpoint is `permitAll` because the client fetches the URL without a bearer
 * header), and the placeholder is published in this repository, so a node booting
 * with it would let anyone forge a token for any job id. Failing closed at startup
 * is cheaper than a silent, unauthenticated report leak.
 */
@Component
class ReportDownloadSecretGuard(
    private val properties: ReportProperties,
    private val environment: Environment,
) {

    @PostConstruct
    fun verify() {
        if (environment.activeProfiles.contains(TEST_PROFILE)) return
        val secret = properties.downloadSecret
        if (secret.isBlank() || secret == PLACEHOLDER_SECRET) {
            throw IllegalStateException(
                "app.reports.download-secret is still the development placeholder. Set REPORT_DOWNLOAD_SECRET to a " +
                    "long random value before starting outside the test profile: it signs every report download link.",
            )
        }
    }

    private companion object {
        const val TEST_PROFILE = "test"
        const val PLACEHOLDER_SECRET = "dev-only-report-download-secret-change-me"
    }
}
