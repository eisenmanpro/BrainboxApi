package com.afrithecus.brainbox.api.media.security

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.media.MediaSecuritySettings
import com.afrithecus.brainbox.api.media.MediaScanStatus
import com.afrithecus.brainbox.api.storage.ObjectStorage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The console-facing half of upload security: every scan is logged, anything that fails or
 * is refused raises an alert, and a refused object is quarantined by moving its bytes to a
 * key that is never served.
 *
 * Nothing here is on the happy path for a deployment without a scanner: with no provider
 * configured the scan log still records `SKIPPED`, and no alert or quarantine row is
 * written.
 */
@Service
class MediaSecurityService(
    private val scans: MediaScanLogRepository,
    private val alerts: MediaSecurityAlertRepository,
    private val quarantine: MediaQuarantineRepository,
    private val settings: MediaSecuritySettings,
    @Qualifier("mediaObjectStorage") private val storage: ObjectStorage,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Records one scan verdict and its policy outcome. */
    @Transactional
    fun recordScan(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        provider: String,
        status: MediaScanStatus,
        detail: String?,
        sizeBytes: Long?,
        contentType: String?,
        durationMs: Long,
        action: MediaScanAction,
    ) {
        scans.save(
            MediaScanLogEntity().apply {
                this.uploadId = uploadId
                this.storageKey = storageKey
                this.ownerId = ownerId
                this.provider = provider
                this.status = status.name
                this.detail = detail?.take(255)
                this.sizeBytes = sizeBytes
                this.contentType = contentType?.take(128)
                this.durationMs = durationMs
                this.action = action.name
            }
        )
    }

    /**
     * Raises an alert unless the deployment has switched alerts off. Severity follows the
     * kind: an infection is HIGH, a scanner that could not run is MEDIUM, a URL refusal is
     * MEDIUM.
     */
    @Transactional
    fun alert(
        kind: MediaAlertKind,
        detail: String?,
        uploadId: UUID? = null,
        storageKey: String? = null,
        url: String? = null,
        ownerId: UUID? = null,
        provider: String? = null,
        severity: MediaAlertSeverity = defaultSeverity(kind),
    ): MediaSecurityAlertEntity? {
        if (!settings.alertsEnabled()) return null
        val row = alerts.save(
            MediaSecurityAlertEntity().apply {
                this.kind = kind.name
                this.severity = severity.name
                this.uploadId = uploadId
                this.storageKey = storageKey?.take(255)
                this.url = url?.take(512)
                this.ownerId = ownerId
                this.provider = provider
                this.detail = detail?.take(500)
            }
        )
        log.warn("media security alert {} for {}: {}", kind, storageKey ?: url ?: "-", detail)
        return row
    }

    /**
     * Moves a refused object out of its serving key into quarantine. The bytes are copied
     * first and the original is only deleted once the copy exists, so a storage failure
     * leaves the object where it was rather than destroying it.
     */
    @Transactional
    fun quarantine(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        reason: String,
        detail: String?,
        sizeBytes: Long?,
        contentType: String?,
    ): MediaQuarantineEntity? {
        val bytes = storage.get(storageKey) ?: return null
        val quarantineKey = QUARANTINE_PREFIX + storageKey
        return try {
            storage.put(quarantineKey, bytes, contentType ?: "application/octet-stream")
            storage.delete(storageKey)
            val row = quarantine.save(
                MediaQuarantineEntity().apply {
                    this.uploadId = uploadId
                    this.originalKey = storageKey
                    this.quarantineKey = quarantineKey
                    this.ownerId = ownerId
                    this.reason = reason
                    this.detail = detail?.take(255)
                    this.sizeBytes = sizeBytes ?: bytes.size.toLong()
                    this.contentType = contentType?.take(128)
                    this.status = QUARANTINED
                }
            )
            alert(
                kind = MediaAlertKind.QUARANTINE,
                detail = "Object quarantined: " + (detail ?: reason),
                uploadId = uploadId,
                storageKey = storageKey,
                ownerId = ownerId,
                severity = MediaAlertSeverity.HIGH,
            )
            row
        } catch (failure: Exception) {
            log.error("could not quarantine {}: {}", storageKey, failure.message)
            null
        }
    }

    /** Console action: puts a quarantined object back at its original key. */
    @Transactional
    fun restore(operator: UserEntity, quarantineId: String): MediaQuarantineEntity {
        val row = requireQuarantined(quarantineId)
        val bytes = storage.get(row.quarantineKey)
            ?: throw notFound("The quarantined object is no longer in storage")
        storage.put(row.originalKey, bytes, row.contentType ?: "application/octet-stream")
        storage.delete(row.quarantineKey)
        row.status = RESTORED
        row.resolvedBy = operator.id
        row.resolvedAt = clock.instant()
        return quarantine.save(row)
    }

    /** Console action: destroys a quarantined object for good. */
    @Transactional
    fun deleteQuarantined(operator: UserEntity, quarantineId: String): MediaQuarantineEntity {
        val row = requireQuarantined(quarantineId)
        storage.delete(row.quarantineKey)
        row.status = DELETED
        row.resolvedBy = operator.id
        row.resolvedAt = clock.instant()
        return quarantine.save(row)
    }

    /** Console write: acknowledges an alert so the open queue stays actionable. */
    @Transactional
    fun acknowledge(operator: UserEntity, alertId: String): MediaSecurityAlertEntity {
        val row = alerts.findById(parseId(alertId, "alert id")).orElse(null)
            ?: throw notFound("Alert not found")
        if (!row.acknowledged) {
            row.acknowledged = true
            row.acknowledgedBy = operator.id
            row.acknowledgedAt = clock.instant()
            alerts.save(row)
        }
        return row
    }

    @Transactional(readOnly = true)
    fun scanLogs(status: String?, limit: Int): List<MediaScanLogEntity> {
        val page = PageRequest.of(0, limit.coerceIn(1, MAX_PAGE))
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return scans.findAllByOrderByCreatedAtDesc(page)
        if (normalized !in KNOWN_SCAN_STATUSES) throw invalidArgument("status must be CLEAN, INFECTED, SKIPPED or ERROR")
        return scans.findAllByStatusOrderByCreatedAtDesc(normalized, page)
    }

    @Transactional(readOnly = true)
    fun alertQueue(acknowledged: Boolean?, limit: Int): List<MediaSecurityAlertEntity> {
        val page = PageRequest.of(0, limit.coerceIn(1, MAX_PAGE))
        return if (acknowledged == null) {
            alerts.findAllByOrderByCreatedAtDesc(page)
        } else {
            alerts.findAllByAcknowledgedOrderByCreatedAtDesc(acknowledged, page)
        }
    }

    @Transactional(readOnly = true)
    fun quarantineQueue(status: String?, limit: Int): List<MediaQuarantineEntity> {
        val page = PageRequest.of(0, limit.coerceIn(1, MAX_PAGE))
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return if (normalized == null) {
            quarantine.findAllByOrderByCreatedAtDesc(page)
        } else {
            if (normalized !in KNOWN_QUARANTINE_STATUSES) {
                throw invalidArgument("status must be QUARANTINED, RESTORED or DELETED")
            }
            quarantine.findAllByStatusOrderByCreatedAtDesc(normalized, page)
        }
    }

    /** Counts for the console's headline tiles. */
    @Transactional(readOnly = true)
    fun summary(): MediaSecuritySummary = MediaSecuritySummary(
        scansByStatus = KNOWN_SCAN_STATUSES.associateWith { scans.countByStatus(it) },
        openAlerts = alerts.countByAcknowledged(false),
        acknowledgedAlerts = alerts.countByAcknowledged(true),
        infectedOpen = alerts.countByKindAndAcknowledged(MediaAlertKind.INFECTED.name, false),
        quarantined = quarantine.countByStatus(QUARANTINED),
        scanEnabled = settings.scanEnabled(),
        scanProvider = settings.scanProvider(),
        urlReputationEnabled = settings.urlReputationEnabled(),
        urlReputationProvider = settings.urlReputationProvider(),
        onInfection = settings.onInfection(),
    )

    private fun requireQuarantined(quarantineId: String): MediaQuarantineEntity {
        val row = quarantine.findById(parseId(quarantineId, "quarantine id")).orElse(null)
            ?: throw notFound("Quarantine entry not found")
        if (row.status != QUARANTINED) throw invalidArgument("This entry is already " + row.status.lowercase())
        return row
    }

    private fun parseId(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull() ?: throw invalidArgument("$field is not a valid identifier")

    private fun defaultSeverity(kind: MediaAlertKind): MediaAlertSeverity = when (kind) {
        MediaAlertKind.INFECTED, MediaAlertKind.QUARANTINE -> MediaAlertSeverity.HIGH
        MediaAlertKind.SCAN_ERROR, MediaAlertKind.URL_BLOCKED -> MediaAlertSeverity.MEDIUM
        MediaAlertKind.URL_ERROR -> MediaAlertSeverity.LOW
    }

    companion object {
        const val QUARANTINED = "QUARANTINED"
        const val RESTORED = "RESTORED"
        const val DELETED = "DELETED"

        /** Quarantined keys are never served: the media route resolves real keys only. */
        const val QUARANTINE_PREFIX = "quarantine/"

        private const val MAX_PAGE = 200
        private val KNOWN_SCAN_STATUSES = setOf("CLEAN", "INFECTED", "SKIPPED", "ERROR")
        private val KNOWN_QUARANTINE_STATUSES = setOf(QUARANTINED, RESTORED, DELETED)
    }
}

/** The console's headline numbers for media security. */
data class MediaSecuritySummary(
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
