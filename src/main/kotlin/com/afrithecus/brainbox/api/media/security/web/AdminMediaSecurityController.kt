package com.afrithecus.brainbox.api.media.security.web

import com.afrithecus.brainbox.api.content.ModerationPolicyService
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.PlatformOperator
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.media.MediaScannerRegistry
import com.afrithecus.brainbox.api.media.MediaSecuritySettings
import com.afrithecus.brainbox.api.media.UrlReputationService
import com.afrithecus.brainbox.api.media.security.MediaQuarantineEntity
import com.afrithecus.brainbox.api.media.security.MediaScanLogEntity
import com.afrithecus.brainbox.api.media.security.MediaSecurityAlertEntity
import com.afrithecus.brainbox.api.media.security.MediaSecurityService
import com.afrithecus.brainbox.api.media.security.MediaSecuritySummary
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Media security for the admin console: the effective posture, the scan log, the alert
 * queue and the quarantine, plus an on-demand URL check.
 *
 * **Authorization is two-layered.** `hasRole('ADMIN')` gets an account through the door, and
 * then the specific platform permission decides what it may do: reads need
 * `CONSOLE_READ`, anything that changes the posture or resolves an object needs
 * `SECURITY_OPERATE`, and managing operators needs `PLATFORM_ADMIN`. An ordinary
 * administrator therefore cannot disable upload scanning, destroy a quarantined object or
 * read the log by virtue of being an admin.
 *
 * Every mutating call is audited with the actor, the target and the change, so the console
 * shows who did what from the server's own record rather than its own.
 */
@RestController
@RequestMapping("/admin/media/security")
@PreAuthorize("hasRole('ADMIN')")
class AdminMediaSecurityController(
    private val security: MediaSecurityService,
    private val settings: MediaSecuritySettings,
    private val policies: ModerationPolicyService,
    private val scanners: MediaScannerRegistry,
    private val urlReputation: UrlReputationService,
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
) {

    @GetMapping("/settings")
    fun settings(@AuthenticationPrincipal current: CurrentUser): MediaSecuritySettingsPayload {
        access.requireSecurityRead(current)
        return settingsPayload()
    }

    /**
     * Partial update of the runtime posture. Only the fields present are written, so a
     * console toggle cannot accidentally reset the rest. Audited field by field.
     */
    @PutMapping("/settings")
    fun updateSettings(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: MediaSecuritySettingsRequest,
    ): MediaSecuritySettingsPayload {
        val operator = access.requireSecurityOperate(current)
        val before = settings.snapshot()
        request.scanEnabled?.let { policies.setMediaScanEnabled(it) }
        request.scanProvider?.let { policies.setMediaScanProvider(it) }
        request.scanFailOpen?.let { policies.setMediaScanFailOpen(it) }
        request.onInfection?.let { policies.setMediaOnInfection(it) }
        request.alertsEnabled?.let { policies.setMediaAlertEnabled(it) }
        request.urlReputationEnabled?.let { policies.setMediaUrlReputationEnabled(it) }
        request.urlReputationProvider?.let { policies.setMediaUrlReputationProvider(it) }
        request.urlReputationFailOpen?.let { policies.setMediaUrlReputationFailOpen(it) }
        val after = settings.snapshot()
        audit.recordPlatform(
            actor = operator,
            action = "media_security_settings_updated",
            target = "settings",
            detail = changedFields(before, after),
        )
        return settingsPayload()
    }

    @GetMapping("/summary")
    fun summary(@AuthenticationPrincipal current: CurrentUser): MediaSecuritySummaryPayload {
        access.requireSecurityRead(current)
        return security.summary().toPayload()
    }

    /** The scan log, newest first, optionally filtered to one verdict. */
    @GetMapping("/scans")
    fun scans(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<MediaScanLogPayload> {
        access.requireSecurityRead(current)
        return security.scanLogs(status, limit).map { it.toPayload() }
    }

    /** The alert queue. `acknowledged=false` is the console's default working view. */
    @GetMapping("/alerts")
    fun alerts(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) acknowledged: Boolean?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<MediaSecurityAlertPayload> {
        access.requireSecurityRead(current)
        return security.alertQueue(acknowledged, limit).map { it.toPayload() }
    }

    @PostMapping("/alerts/{alertId}/ack")
    fun acknowledge(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable alertId: String,
    ): MediaSecurityAlertPayload {
        val operator = access.requireSecurityOperate(current)
        val alert = security.acknowledge(operator, alertId)
        audit.recordPlatform(
            actor = operator,
            action = "media_security_alert_acknowledged",
            target = "alert:" + alert.id,
            detail = alert.kind + (alert.storageKey?.let { " on " + it } ?: ""),
        )
        return alert.toPayload()
    }

    /** Quarantined objects: inspect, restore or destroy. */
    @GetMapping("/quarantine")
    fun quarantine(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<MediaQuarantinePayload> {
        access.requireSecurityRead(current)
        return security.quarantineQueue(status, limit).map { it.toPayload() }
    }

    @PostMapping("/quarantine/{quarantineId}/restore")
    fun restore(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable quarantineId: String,
    ): MediaQuarantinePayload {
        val operator = access.requireSecurityOperate(current)
        val row = security.restore(operator, quarantineId)
        audit.recordPlatform(
            actor = operator,
            action = "media_quarantine_restored",
            target = "quarantine:" + row.id,
            detail = "restored " + row.originalKey,
        )
        return row.toPayload()
    }

    @DeleteMapping("/quarantine/{quarantineId}")
    fun deleteQuarantined(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable quarantineId: String,
    ): MediaQuarantinePayload {
        val operator = access.requireSecurityOperate(current)
        val row = security.deleteQuarantined(operator, quarantineId)
        audit.recordPlatform(
            actor = operator,
            action = "media_quarantine_deleted",
            target = "quarantine:" + row.id,
            detail = "destroyed " + row.originalKey + " (" + row.reason + ")",
        )
        return row.toPayload()
    }

    /** Checks one URL with the configured reputation provider (a console action). */
    @PostMapping("/url-check")
    fun urlCheck(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: UrlCheckRequest,
    ): UrlCheckResponse {
        access.requireSecurityRead(current)
        val result = urlReputation.enforce(request.url)
        return UrlCheckResponse(
            status = result.status.name,
            detail = result.detail,
            provider = result.provider,
            allowed = result.allowed,
        )
    }

    // ------------------------------------------------------------- operators

    /** The operator manager: who holds which platform permission. */
    @GetMapping("/operators")
    fun operators(@AuthenticationPrincipal current: CurrentUser): List<PlatformOperatorPayload> {
        access.requirePlatformAdmin(current)
        return access.operators().map { it.toPayload() }
    }

    @PostMapping("/operators/{userId}/permissions")
    fun grantPermissions(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @Valid @RequestBody request: PlatformPermissionRequest,
    ): PlatformOperatorPayload =
        access.grant(current, userId, request.permissions).toPayload()

    @DeleteMapping("/operators/{userId}/permissions")
    fun revokePermissions(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable userId: String,
        @Valid @RequestBody request: PlatformPermissionRequest,
    ): PlatformOperatorPayload =
        access.revoke(current, userId, request.permissions).toPayload()

    // ---------------------------------------------------------------- internals

    private fun settingsPayload(): MediaSecuritySettingsPayload {
        val snapshot = settings.snapshot()
        return MediaSecuritySettingsPayload(
            scanEnabled = snapshot.scanEnabled,
            scanProvider = snapshot.scanProvider,
            scannerConfigured = snapshot.scannerConfigured,
            scanFailOpen = snapshot.scanFailOpen,
            onInfection = snapshot.onInfection,
            alertsEnabled = snapshot.alertsEnabled,
            urlReputationEnabled = snapshot.urlReputationEnabled,
            urlReputationProvider = snapshot.urlReputationProvider,
            urlReputationFailOpen = snapshot.urlReputationFailOpen,
            clamavHost = snapshot.clamavHost,
            clamavPort = snapshot.clamavPort,
            scanTimeoutMillis = snapshot.scanTimeoutMillis,
            maxScanBytes = snapshot.maxScanBytes,
            urlCheckUrl = snapshot.urlCheckUrl,
            availableScanProviders = scanners.names().sorted(),
            availableUrlProviders = listOf("none", "deny_list", "http"),
        )
    }

    /** The posture fields that actually moved, for the audit detail. */
    private fun changedFields(
        before: com.afrithecus.brainbox.api.media.MediaSecuritySettingsSnapshot,
        after: com.afrithecus.brainbox.api.media.MediaSecuritySettingsSnapshot,
    ): String {
        val changes = mutableListOf<String>()
        if (before.scanEnabled != after.scanEnabled) changes += "scanEnabled=$before->${after.scanEnabled}"
        if (before.scanProvider != after.scanProvider) changes += "scanProvider=${before.scanProvider}->${after.scanProvider}"
        if (before.scanFailOpen != after.scanFailOpen) changes += "scanFailOpen=${before.scanFailOpen}->${after.scanFailOpen}"
        if (before.onInfection != after.onInfection) changes += "onInfection=${before.onInfection}->${after.onInfection}"
        if (before.alertsEnabled != after.alertsEnabled) changes += "alertsEnabled=${before.alertsEnabled}->${after.alertsEnabled}"
        if (before.urlReputationEnabled != after.urlReputationEnabled) {
            changes += "urlReputationEnabled=${before.urlReputationEnabled}->${after.urlReputationEnabled}"
        }
        if (before.urlReputationProvider != after.urlReputationProvider) {
            changes += "urlReputationProvider=${before.urlReputationProvider}->${after.urlReputationProvider}"
        }
        if (before.urlReputationFailOpen != after.urlReputationFailOpen) {
            changes += "urlReputationFailOpen=${before.urlReputationFailOpen}->${after.urlReputationFailOpen}"
        }
        return if (changes.isEmpty()) "no effective change" else changes.joinToString("; ").take(500)
    }
}

/** One operator and its permissions, as the operator manager reads it. */
data class PlatformOperatorPayload(
    val userId: String,
    val name: String,
    val phoneNumber: String,
    val permissions: List<String>,
    /** True when this account can manage other operators. */
    val platformAdmin: Boolean,
    /** True when the account has no permission at all (an ordinary administrator). */
    val unprivileged: Boolean,
)

/** Grant/revoke body: permission names, validated against the enum. */
data class PlatformPermissionRequest(
    val permissions: List<String> = emptyList(),
)

private fun PlatformOperator.toPayload() = PlatformOperatorPayload(
    userId = userId.toString(),
    name = name,
    phoneNumber = phoneNumber,
    permissions = permissions.map { it.name }.sorted(),
    platformAdmin = has(com.afrithecus.brainbox.api.identity.PlatformPermission.PLATFORM_ADMIN),
    unprivileged = permissions.isEmpty(),
)

private fun MediaSecuritySummary.toPayload() = MediaSecuritySummaryPayload(
    scansByStatus = scansByStatus,
    openAlerts = openAlerts,
    acknowledgedAlerts = acknowledgedAlerts,
    infectedOpen = infectedOpen,
    quarantined = quarantined,
    scanEnabled = scanEnabled,
    scanProvider = scanProvider,
    urlReputationEnabled = urlReputationEnabled,
    urlReputationProvider = urlReputationProvider,
    onInfection = onInfection,
)

private fun MediaScanLogEntity.toPayload() = MediaScanLogPayload(
    id = id.toString(),
    uploadId = uploadId?.toString(),
    storageKey = storageKey,
    ownerId = ownerId?.toString(),
    provider = provider,
    status = status,
    detail = detail,
    sizeBytes = sizeBytes,
    contentType = contentType,
    durationMs = durationMs,
    action = action,
    createdAt = createdAt.toEpochMilli(),
)

private fun MediaSecurityAlertEntity.toPayload() = MediaSecurityAlertPayload(
    id = id.toString(),
    kind = kind,
    severity = severity,
    uploadId = uploadId?.toString(),
    storageKey = storageKey,
    url = url,
    ownerId = ownerId?.toString(),
    provider = provider,
    detail = detail,
    acknowledged = acknowledged,
    acknowledgedAt = acknowledgedAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
)

private fun MediaQuarantineEntity.toPayload() = MediaQuarantinePayload(
    id = id.toString(),
    uploadId = uploadId?.toString(),
    originalKey = originalKey,
    reason = reason,
    detail = detail,
    sizeBytes = sizeBytes,
    contentType = contentType,
    status = status,
    resolvedAt = resolvedAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
)
