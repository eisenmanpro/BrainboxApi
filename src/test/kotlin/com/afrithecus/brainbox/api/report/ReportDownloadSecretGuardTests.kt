package com.afrithecus.brainbox.api.report

import org.springframework.mock.env.MockEnvironment
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The download secret is the only authorization on the public download route, so
 * the placeholder must never be accepted outside tests.
 */
class ReportDownloadSecretGuardTests {

    @Test
    fun placeholderIsAcceptedOnlyUnderTheTestProfile() {
        guard(PLACEHOLDER, "test").verify()
        assertFailsWith<IllegalStateException> { guard(PLACEHOLDER).verify() }
        assertFailsWith<IllegalStateException> { guard(PLACEHOLDER, "prod").verify() }
    }

    @Test
    fun configuredSecretBootsAndBlankSecretNeverDoes() {
        guard("a-real-long-random-secret-value", "prod").verify()
        guard("a-real-long-random-secret-value").verify()
        assertFailsWith<IllegalStateException> { guard("").verify() }
        assertFailsWith<IllegalStateException> { guard("", "prod").verify() }
    }

    private fun guard(secret: String, vararg profiles: String): ReportDownloadSecretGuard {
        val environment = MockEnvironment()
        environment.setActiveProfiles(*profiles)
        return ReportDownloadSecretGuard(ReportProperties(downloadSecret = secret), environment)
    }

    private companion object {
        const val PLACEHOLDER = "dev-only-report-download-secret-change-me"
    }
}
