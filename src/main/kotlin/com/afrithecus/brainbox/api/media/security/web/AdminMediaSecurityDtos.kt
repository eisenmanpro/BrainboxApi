package com.afrithecus.brainbox.api.media.security.web

/**
 * The admin console's media security surface (`docs/ongoing/api_media_security_changes.md`).
 * Settings are runtime policy: a deployment with ClamAV turns it on here (or by config) and
 * can relax URL reputation or switch infection handling without a release.
 */
data class MediaSecuritySettingsPayload(
    val scanEnabled: Boolean,
    val scanProvider: String,
    /** True when the deployment itself configured a scanner (ClamAV, say). */
    val scannerConfigured: Boolean,
    val scanFailOpen: Boolean,
    /** QUARANTINE or DELETE. */
    val onInfection: String,
    val alertsEnabled: Boolean,
    val urlReputationEnabled: Boolean,
    val urlReputationProvider: String,
    val urlReputationFailOpen: Boolean,
    val clamavHost: String,
    val clamavPort: Int,
    val scanTimeoutMillis: Long,
    val maxScanBytes: Long,
    val urlCheckUrl: String,
    val availableScanProviders: List<String> = emptyList(),
    val availableUrlProviders: List<String> = emptyList(),
)

/** Partial update: a null field is left unchanged, so a console form can send one toggle. */
data class MediaSecuritySettingsRequest(
    val scanEnabled: Boolean? = null,
    val scanProvider: String? = null,
    val scanFailOpen: Boolean? = null,
    val onInfection: String? = null,
    val alertsEnabled: Boolean? = null,
    val urlReputationEnabled: Boolean? = null,
    val urlReputationProvider: String? = null,
    val urlReputationFailOpen: Boolean? = null,
)

data class MediaScanLogPayload(
    val id: String,
    val uploadId: String? = null,
    val storageKey: String,
    val ownerId: String? = null,
    val provider: String,
    /** CLEAN, INFECTED, SKIPPED or ERROR. */
    val status: String,
    val detail: String? = null,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    val durationMs: Long,
    /** ACCEPTED, QUARANTINED, DELETED or REFUSED. */
    val action: String,
    val createdAt: Long,
)

data class MediaSecurityAlertPayload(
    val id: String,
    /** INFECTED, SCAN_ERROR, URL_BLOCKED, URL_ERROR or QUARANTINE. */
    val kind: String,
    /** HIGH, MEDIUM or LOW. */
    val severity: String,
    val uploadId: String? = null,
    val storageKey: String? = null,
    val url: String? = null,
    val ownerId: String? = null,
    val provider: String? = null,
    val detail: String? = null,
    val acknowledged: Boolean,
    val acknowledgedAt: Long? = null,
    val createdAt: Long,
)

data class MediaQuarantinePayload(
    val id: String,
    val uploadId: String? = null,
    val originalKey: String,
    val reason: String,
    val detail: String? = null,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    /** QUARANTINED, RESTORED or DELETED. */
    val status: String,
    val resolvedAt: Long? = null,
    val createdAt: Long,
)

/** The headline tiles: what was scanned, what is open, what is held. */
data class MediaSecuritySummaryPayload(
    val scansByStatus: Map<String, Long>,
    val openAlerts: Long,
    val acknowledgedAlerts: Long,
    val infectedOpen: Long,
    val quarantined: Long,
    val scanEnabled: Boolean,
    val scanProvider: String,
    val urlReputationEnabled: Boolean,
    val urlReputationProvider: String,
    val onInfection: String,
)

/** An operator's on-demand URL check. */
data class UrlCheckRequest(val url: String)

data class UrlCheckResponse(
    /** CLEAN, BLOCKED, SKIPPED or ERROR. */
    val status: String,
    val detail: String? = null,
    val provider: String,
    /** Whether the caller should treat the URL as usable. */
    val allowed: Boolean,
)
