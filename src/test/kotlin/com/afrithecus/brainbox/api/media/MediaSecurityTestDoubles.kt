package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.content.ModerationPolicyService
import com.afrithecus.brainbox.api.media.security.MediaSecurityService
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Builds a scan gate for the unit tests that only need the storage/URL behaviour of
 * `MediaService`/`MediaUploadService`: the runtime settings are mocked, and the security
 * service (scan log, alerts, quarantine) is a mock because its real behaviour is covered
 * end-to-end in `MediaSecurityWebTests` against real repositories.
 */
object MediaSecurityTestDoubles {

    fun settings(
        scanEnabled: Boolean = false,
        provider: String = "none",
        failOpen: Boolean = false,
        onInfection: String = "QUARANTINE",
    ): MediaSecuritySettings {
        val policies = mock(ModerationPolicyService::class.java)
        `when`(policies.mediaScanEnabled(anyBoolean())).thenReturn(scanEnabled)
        `when`(policies.mediaScanProvider(anyString())).thenReturn(provider)
        `when`(policies.mediaScanFailOpen(anyBoolean())).thenReturn(failOpen)
        `when`(policies.mediaOnInfection(anyString())).thenReturn(onInfection)
        `when`(policies.mediaAlertEnabled(anyBoolean())).thenReturn(true)
        `when`(policies.mediaUrlReputationEnabled(anyBoolean())).thenReturn(false)
        `when`(policies.mediaUrlReputationProvider(anyString())).thenReturn("none")
        `when`(policies.mediaUrlReputationFailOpen(anyBoolean())).thenReturn(false)
        return MediaSecuritySettings(
            policies = policies,
            properties = MediaProperties(
                scan = MediaProperties.Scan(provider = provider, failOpen = failOpen),
            ),
            configuredScanProvider = provider,
            configuredFailOpen = failOpen,
            configuredUrlReputation = false,
            configuredUrlProvider = "none",
            configuredUrlFailOpen = false,
        )
    }

    fun security(): MediaSecurityService = mock(MediaSecurityService::class.java)

    fun gate(
        scanner: MediaScanner = NoOpMediaScanner(),
        settings: MediaSecuritySettings = settings(),
        security: MediaSecurityService = security(),
        properties: MediaProperties = MediaProperties(),
    ): MediaScanGate = MediaScanGate(
        registry = MediaScannerRegistry(mapOf("none" to scanner)),
        properties = properties,
        settings = settings,
        security = security,
    )
}
