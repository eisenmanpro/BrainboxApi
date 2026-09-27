package com.afrithecus.brainbox.api.content.validation

import com.afrithecus.brainbox.api.content.ModerationPolicyService
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.media.MediaProperties
import com.afrithecus.brainbox.api.media.MediaSecuritySettings
import com.afrithecus.brainbox.api.media.UrlReputationService
import com.afrithecus.brainbox.api.media.security.MediaSecurityService
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * URL reputation inside the content pipeline: a generated IMAGE URL is checked before a
 * learner ever sees it, a refused URL is a BLOCKER (so the supervisor loop revises) and an
 * unchecked URL is a WARNING — a reputation outage must not stop content production, but it
 * must be visible and alerted.
 *
 * The findings are this validator's contract. The alert it also raises is asserted against
 * the real security service in `MediaSecurityWebTests`.
 */
class MediaUrlValidatorTests {

    private val mapper = ObjectMapper()

    /** The settings a deployment or console would set, mocked at the policy seam. */
    private fun securitySettings(
        enabled: Boolean,
        provider: String,
        failOpen: Boolean = false,
        httpUrl: String = "",
    ): MediaSecuritySettings {
        val policies = mock(ModerationPolicyService::class.java)
        `when`(policies.mediaUrlReputationEnabled(anyBoolean())).thenReturn(enabled)
        `when`(policies.mediaUrlReputationProvider(anyString())).thenReturn(provider)
        `when`(policies.mediaUrlReputationFailOpen(anyBoolean())).thenReturn(failOpen)
        `when`(policies.mediaScanEnabled(anyBoolean())).thenReturn(false)
        `when`(policies.mediaScanProvider(anyString())).thenReturn("none")
        `when`(policies.mediaScanFailOpen(anyBoolean())).thenReturn(false)
        `when`(policies.mediaOnInfection(anyString())).thenReturn("QUARANTINE")
        `when`(policies.mediaAlertEnabled(anyBoolean())).thenReturn(true)
        return MediaSecuritySettings(
            policies = policies,
            properties = MediaProperties(
                urlReputation = MediaProperties.UrlReputation(
                    enabled = enabled,
                    provider = provider,
                    failOpen = failOpen,
                    http = MediaProperties.UrlReputation.Http(url = httpUrl),
                ),
            ),
            configuredScanProvider = "none",
            configuredFailOpen = false,
            configuredUrlReputation = enabled,
            configuredUrlProvider = provider,
            configuredUrlFailOpen = failOpen,
        )
    }

    /**
     * The service reads the *endpoint* from deployment properties (a console form must not be
     * able to point the API at an arbitrary host) and everything else from the runtime
     * settings, so the test wires the same endpoint into both.
     */
    private fun validator(
        settings: MediaSecuritySettings,
        alerts: MediaSecurityService,
        endpoint: String = "",
    ): MediaUrlValidator = MediaUrlValidator(
        UrlReputationService(
            MediaProperties(
                urlReputation = MediaProperties.UrlReputation(
                    http = MediaProperties.UrlReputation.Http(url = endpoint),
                ),
            ),
            settings,
            mapper,
        ),
        alerts,
        mapper,
    )

    /** A unit whose single step carries an IMAGE figure with [url]. */
    private fun context(url: String): ValidationContext {
        val unit = ContentUnitEntity().apply {
            generationKey = "ke:cbc:grade4:mat-num-frac:lesson:media-url"
            subject = "Mathematics"
            gradeLevel = "Grade 4"
            language = "en"
            standardVersion = "v1"
            body = "Fractions."
        }
        val step = ContentUnitStepEntity().apply {
            unitId = unit.id
            orderIndex = 0
            body = "Look at the picture."
            figureSpec = """{"kind":"IMAGE","url":"$url","alt":"A pizza split into quarters"}"""
        }
        return ValidationContext(
            contentType = "CONTENT_UNIT",
            unit = unit,
            steps = listOf(step),
            questions = emptyList(),
            concept = null,
            curriculumMapping = null,
        )
    }

    @Test
    fun `disabled reputation does not inspect anything`() {
        val alerts = mock(MediaSecurityService::class.java)
        val findings = validator(securitySettings(enabled = false, provider = "deny_list"), alerts)
            .validate(context("http://127.0.0.1/private.png"))
        assertTrue(findings.isEmpty(), "off by default, so nothing is checked")
    }

    @Test
    fun `a denied url blocks the unit and alerts`() {
        val alerts = mock(MediaSecurityService::class.java)
        val findings = validator(securitySettings(enabled = true, provider = "deny_list"), alerts)
            .validate(context("http://127.0.0.1/private.png"))

        assertEquals(1, findings.size)
        assertEquals(FindingSeverity.BLOCKER, findings.single().severity)
        assertEquals("MEDIA_URL_BLOCKED", findings.single().code)

    }

    @Test
    fun `an allowed url produces no finding`() {
        val alerts = mock(MediaSecurityService::class.java)
        val findings = validator(securitySettings(enabled = true, provider = "deny_list"), alerts)
            .validate(context("https://cdn.example.org/fractions.png"))
        assertTrue(findings.isEmpty(), "a public https URL is fine")
    }

    @Test
    fun `an unreadable provider warns rather than blocking content`() {
        val alerts = mock(MediaSecurityService::class.java)
        val findings = validator(securitySettings(enabled = true, provider = "http", httpUrl = ""), alerts)
            .validate(context("https://cdn.example.org/fractions.png"))

        assertEquals(1, findings.size)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
        assertEquals("MEDIA_URL_UNCHECKED", findings.single().code)

    }

    @Test
    fun `the http provider's verdict decides`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/check") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            val verdict = if (body.contains("evil.example.org")) {
                """{"status":"MALICIOUS","detail":"phishing"}"""
            } else {
                """{"status":"CLEAN"}"""
            }
            val bytes = verdict.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:" + server.address.port + "/check"
            val blocked = validator(
                securitySettings(enabled = true, provider = "http", httpUrl = url),
                mock(MediaSecurityService::class.java),
                endpoint = url,
            ).validate(context("https://evil.example.org/a.png"))
            assertEquals("MEDIA_URL_BLOCKED", blocked.single().code)
            assertEquals(FindingSeverity.BLOCKER, blocked.single().severity)

            val clean = validator(
                securitySettings(enabled = true, provider = "http", httpUrl = url),
                mock(MediaSecurityService::class.java),
                endpoint = url,
            ).validate(context("https://good.example.org/a.png"))
            assertTrue(clean.isEmpty(), "a clean verdict adds no finding")

            // A blocked URL is refused even with fail-open: fail-open is for outages, not for
            // a provider that answered.
            val failOpen = validator(
                securitySettings(enabled = true, provider = "http", failOpen = true, httpUrl = url),
                mock(MediaSecurityService::class.java),
                endpoint = url,
            ).validate(context("https://evil.example.org/a.png"))
            assertEquals("MEDIA_URL_BLOCKED", failOpen.single().code)
        } finally {
            server.stop(0)
        }
    }
}
