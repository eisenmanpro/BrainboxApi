package com.afrithecus.brainbox.api.media

import com.afrithecus.brainbox.api.media.security.MediaAlertKind
import com.afrithecus.brainbox.api.media.security.MediaScanAction
import com.afrithecus.brainbox.api.media.security.MediaSecurityService
import org.springframework.stereotype.Service
import java.util.UUID

/** One scanner verdict plus the deployment policy's decision for it. */
data class MediaScanOutcome(
    val allowed: Boolean,
    val status: MediaScanStatus,
    val detail: String? = null,
    val provider: String = "none",
    /** What was done about it: ACCEPTED, QUARANTINED, DELETED or REFUSED. */
    val action: MediaScanAction = MediaScanAction.ACCEPTED,
    /** The quarantine row when the object was moved rather than deleted. */
    val quarantineId: UUID? = null,
)

/** The outcome of a refusal: what was done, and where the bytes are if they were kept. */
private data class Refusal(val action: MediaScanAction, val quarantineId: UUID?)

/**
 * The one place the malware-scan policy lives, so the presigned confirm path and the
 * proxied multipart path cannot disagree.
 *
 * The posture is read per call from [MediaSecuritySettings], which means the console (or a
 * config change) can switch scanning on or off and choose what happens to a refusal without
 * a release:
 *
 * | verdict | default |
 * | --- | --- |
 * | CLEAN | accepted |
 * | SKIPPED (no scanner configured) | accepted and recorded as unscanned |
 * | INFECTED | refused — quarantined by default, deleted when `mediaOnInfection=DELETE` |
 * | ERROR (scanner down, unreadable verdict, unknown status, above the scan cap) | refused unless `mediaScanFailOpen=true`; an accepted error is recorded as `ERROR`, never as clean |
 *
 * Every verdict is logged, and a refusal or a scanner failure raises a console alert.
 */
@Service
class MediaScanGate(
    private val registry: MediaScannerRegistry,
    private val properties: MediaProperties,
    private val settings: MediaSecuritySettings,
    private val security: MediaSecurityService,
) {

    /**
     * Runs the scanner over one object. The bytes are supplied lazily and the known
     * [sizeBytes] is checked first, so an unscanned (no provider) or unscannable (above the
     * cap) object is never read into memory at all.
     */
    fun inspect(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        sizeBytes: Long,
        contentType: String?,
        bytes: () -> ByteArray?,
    ): MediaScanOutcome {
        val providerName = if (settings.scanEnabled()) settings.scanProvider() else "none"
        val scanner = registry.forName(providerName)
        val startedAt = System.nanoTime()

        if (!scanner.enabled) {
            val detail = if (settings.scanEnabled()) {
                "Scanner '$providerName' is not available; the object was not scanned"
            } else {
                "No malware scanner is configured on this deployment"
            }
            return finish(
                uploadId, storageKey, ownerId, "none", MediaScanStatus.SKIPPED, detail,
                sizeBytes, contentType, startedAt, allowed = true,
            )
        }

        if (sizeBytes > properties.scan.maxScanBytes) {
            return refuse(
                uploadId, storageKey, ownerId, providerName, MediaScanStatus.ERROR,
                "Object is larger than the scan limit and was not scanned",
                sizeBytes, contentType, startedAt, alertOnFailure = true,
            )
        }

        val content = bytes()
        if (content == null) {
            return refuse(
                uploadId, storageKey, ownerId, providerName, MediaScanStatus.ERROR,
                "The object could not be read for scanning",
                sizeBytes, contentType, startedAt, alertOnFailure = true,
            )
        }

        // One scan per object: the verdict and its detail must come from the same call.
        val result = scanner.scan(content, contentType)
        return when (result.status) {
            MediaScanStatus.CLEAN, MediaScanStatus.SKIPPED ->
                finish(
                    uploadId, storageKey, ownerId, providerName, result.status, result.detail,
                    sizeBytes, contentType, startedAt, allowed = true,
                )

            MediaScanStatus.INFECTED -> refuse(
                uploadId, storageKey, ownerId, providerName, MediaScanStatus.INFECTED,
                result.detail ?: "malware signature match",
                sizeBytes, contentType, startedAt, alertOnFailure = true,
            )

            MediaScanStatus.ERROR -> refuse(
                uploadId, storageKey, ownerId, providerName, MediaScanStatus.ERROR,
                result.detail ?: "The malware scanner could not scan this file",
                sizeBytes, contentType, startedAt, alertOnFailure = true,
            )
        }
    }

    /**
     * Applies the refusal policy: an error is accepted only when the deployment opted into
     * fail-open; an infection is always refused, and quarantine (the default) keeps the bytes
     * under a key that is never served.
     */
    private fun refuse(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        provider: String,
        status: MediaScanStatus,
        detail: String,
        sizeBytes: Long?,
        contentType: String?,
        startedAt: Long,
        alertOnFailure: Boolean,
    ): MediaScanOutcome {
        val failOpen = status == MediaScanStatus.ERROR && settings.scanFailOpen()
        if (failOpen) {
            return finish(
                uploadId, storageKey, ownerId, provider, status, detail,
                sizeBytes, contentType, startedAt, allowed = true,
            )
        }
        val refusal = when (status) {
            MediaScanStatus.INFECTED -> applyInfectionPolicy(uploadId, storageKey, ownerId, detail, sizeBytes, contentType)
            else -> when (settings.onInfection()) {
                MediaSecuritySettings.INFECTION_DELETE -> Refusal(MediaScanAction.DELETED, null)
                else -> quarantine(uploadId, storageKey, ownerId, "SCAN_ERROR", detail, sizeBytes, contentType)
            }
        }
        return finish(
            uploadId, storageKey, ownerId, provider, status, detail,
            sizeBytes, contentType, startedAt, allowed = false,
            action = refusal.action, quarantineId = refusal.quarantineId, alertOn = alertOnFailure,
        )
    }

    private fun applyInfectionPolicy(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        detail: String,
        sizeBytes: Long?,
        contentType: String?,
    ): Refusal = if (settings.onInfection() == MediaSecuritySettings.INFECTION_DELETE) {
        Refusal(MediaScanAction.DELETED, null)
    } else {
        quarantine(uploadId, storageKey, ownerId, "INFECTED", detail, sizeBytes, contentType)
    }

    /** Moves the object into quarantine; a storage failure degrades to delete. */
    private fun quarantine(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        reason: String,
        detail: String?,
        sizeBytes: Long?,
        contentType: String?,
    ): Refusal {
        val row = security.quarantine(uploadId, storageKey, ownerId, reason, detail, sizeBytes, contentType)
            ?: return Refusal(MediaScanAction.DELETED, null)
        return Refusal(MediaScanAction.QUARANTINED, row.id)
    }

    private fun finish(
        uploadId: UUID?,
        storageKey: String,
        ownerId: UUID?,
        provider: String,
        status: MediaScanStatus,
        detail: String?,
        sizeBytes: Long?,
        contentType: String?,
        startedAt: Long,
        allowed: Boolean,
        action: MediaScanAction = MediaScanAction.ACCEPTED,
        quarantineId: UUID? = null,
        alertOn: Boolean = false,
    ): MediaScanOutcome {
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
        security.recordScan(
            uploadId = uploadId,
            storageKey = storageKey,
            ownerId = ownerId,
            provider = provider,
            status = status,
            detail = detail,
            sizeBytes = sizeBytes,
            contentType = contentType,
            durationMs = durationMs,
            action = if (allowed) MediaScanAction.ACCEPTED else action,
        )
        if (alertOn) {
            security.alert(
                kind = if (status == MediaScanStatus.INFECTED) MediaAlertKind.INFECTED else MediaAlertKind.SCAN_ERROR,
                detail = detail,
                uploadId = uploadId,
                storageKey = storageKey,
                ownerId = ownerId,
                provider = provider,
            )
        }
        return MediaScanOutcome(
            allowed = allowed,
            status = status,
            detail = detail,
            provider = provider,
            action = if (allowed) MediaScanAction.ACCEPTED else action,
            quarantineId = if (allowed) null else quarantineId,
        )
    }

    /** The configured provider, for the audit trail. */
    fun providerName(): String = if (settings.scanEnabled()) settings.scanProvider() else "none"
}
