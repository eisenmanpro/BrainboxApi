package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.content.ModerationPolicyService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * The effective media security posture: the console's stored override when present,
 * otherwise the deployment's `app.media.*` default.
 *
 * Everything here is resolved per call rather than cached at startup, which is what makes
 * switching ClamAV on or off — or flipping infection handling between quarantine and
 * delete — a configuration/console action instead of a release.
 */
@Service
class MediaSecuritySettings(
    private val policies: ModerationPolicyService,
    private val properties: MediaProperties,
    /** Empty/`none` means this deployment has no scanner; ClamAV is opt-in by config. */
    @Value("\${app.media.scan.provider:none}") private val configuredScanProvider: String,
    @Value("\${app.media.scan.fail-open:false}") private val configuredFailOpen: Boolean,
    @Value("\${app.media.url-reputation.enabled:false}") private val configuredUrlReputation: Boolean,
    @Value("\${app.media.url-reputation.provider:none}") private val configuredUrlProvider: String,
    @Value("\${app.media.url-reputation.fail-open:false}") private val configuredUrlFailOpen: Boolean,
) {

    /** True when a scanner is configured for this deployment (the `enabled` default). */
    val scannerConfigured: Boolean get() = configuredScanProvider.trim().lowercase() !in setOf("", "none")

    fun scanEnabled(): Boolean = policies.mediaScanEnabled(scannerConfigured)

    fun scanProvider(): String = policies.mediaScanProvider(configuredScanProvider.ifBlank { "none" })

    fun scanFailOpen(): Boolean = policies.mediaScanFailOpen(configuredFailOpen)

    /** QUARANTINE or DELETE. */
    fun onInfection(): String = policies.mediaOnInfection(INFECTION_QUARANTINE)

    fun alertsEnabled(): Boolean = policies.mediaAlertEnabled(true)

    fun urlReputationEnabled(): Boolean = policies.mediaUrlReputationEnabled(configuredUrlReputation)

    fun urlReputationProvider(): String = policies.mediaUrlReputationProvider(configuredUrlProvider.ifBlank { "none" })

    fun urlReputationFailOpen(): Boolean = policies.mediaUrlReputationFailOpen(configuredUrlFailOpen)

    /** The scanner/URL settings as the console reads them, defaults included. */
    fun snapshot(): MediaSecuritySettingsSnapshot = MediaSecuritySettingsSnapshot(
        scanEnabled = scanEnabled(),
        scanProvider = scanProvider(),
        scanFailOpen = scanFailOpen(),
        onInfection = onInfection(),
        alertsEnabled = alertsEnabled(),
        urlReputationEnabled = urlReputationEnabled(),
        urlReputationProvider = urlReputationProvider(),
        urlReputationFailOpen = urlReputationFailOpen(),
        scannerConfigured = scannerConfigured,
        clamavHost = properties.scan.clamav.host,
        clamavPort = properties.scan.clamav.port,
        scanTimeoutMillis = properties.scan.timeoutMillis,
        maxScanBytes = properties.scan.maxScanBytes,
        urlCheckUrl = properties.urlReputation.http.url,
    )

    companion object {
        const val INFECTION_QUARANTINE = "QUARANTINE"
        const val INFECTION_DELETE = "DELETE"
    }
}

/** The console's view of the effective posture. Secrets (tokens) are never included. */
data class MediaSecuritySettingsSnapshot(
    val scanEnabled: Boolean,
    val scanProvider: String,
    val scanFailOpen: Boolean,
    val onInfection: String,
    val alertsEnabled: Boolean,
    val urlReputationEnabled: Boolean,
    val urlReputationProvider: String,
    val urlReputationFailOpen: Boolean,
    val scannerConfigured: Boolean,
    val clamavHost: String,
    val clamavPort: Int,
    val scanTimeoutMillis: Long,
    val maxScanBytes: Long,
    val urlCheckUrl: String,
)
