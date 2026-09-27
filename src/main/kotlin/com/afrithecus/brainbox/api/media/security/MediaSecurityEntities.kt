package com.afrithecus.brainbox.api.media.security

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** What the deployment did with a scan verdict. */
enum class MediaScanAction { ACCEPTED, QUARANTINED, DELETED, REFUSED }

/** What an operator must look at. */
enum class MediaAlertKind { INFECTED, SCAN_ERROR, URL_BLOCKED, URL_ERROR, QUARANTINE }

enum class MediaAlertSeverity { HIGH, MEDIUM, LOW }

/**
 * One scan, recorded whether it passed or not. This is the console's log view: it answers
 * "was this object scanned, by what, with which verdict, and what did we do about it"
 * without re-deriving anything from the ticket row.
 */
@Entity
@Table(name = "media_scan_logs")
class MediaScanLogEntity : BaseEntity() {

    @Column(name = "upload_id")
    var uploadId: UUID? = null

    @Column(name = "storage_key", nullable = false, length = 120)
    var storageKey: String = ""

    @Column(name = "owner_id")
    var ownerId: UUID? = null

    /** The scanner that produced the verdict (none, clamav, http, deny_list). */
    @Column(nullable = false, length = 32)
    var provider: String = ""

    /** CLEAN, INFECTED, SKIPPED or ERROR. */
    @Column(nullable = false, length = 16)
    var status: String = "SKIPPED"

    @Column(length = 255)
    var detail: String? = null

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    @Column(name = "content_type", length = 128)
    var contentType: String? = null

    @Column(name = "duration_ms", nullable = false)
    var durationMs: Long = 0

    /** ACCEPTED, QUARANTINED, DELETED or REFUSED. */
    @Column(nullable = false, length = 16)
    var action: String = MediaScanAction.ACCEPTED.name
}

/**
 * Something an operator should act on: an infection, a scanner that could not run, or a
 * URL the reputation check refused. Alerts are acknowledgeable so the console queue can
 * be worked rather than only watched.
 */
@Entity
@Table(name = "media_security_alerts")
class MediaSecurityAlertEntity : BaseEntity() {

    /** INFECTED, SCAN_ERROR, URL_BLOCKED, URL_ERROR or QUARANTINE. */
    @Column(nullable = false, length = 32)
    var kind: String = MediaAlertKind.SCAN_ERROR.name

    @Column(nullable = false, length = 16)
    var severity: String = MediaAlertSeverity.HIGH.name

    @Column(name = "upload_id")
    var uploadId: UUID? = null

    @Column(name = "storage_key", length = 255)
    var storageKey: String? = null

    @Column(length = 512)
    var url: String? = null

    @Column(name = "owner_id")
    var ownerId: UUID? = null

    @Column(length = 32)
    var provider: String? = null

    @Column(length = 500)
    var detail: String? = null

    @Column(nullable = false)
    var acknowledged: Boolean = false

    @Column(name = "acknowledged_by")
    var acknowledgedBy: UUID? = null

    @Column(name = "acknowledged_at")
    var acknowledgedAt: Instant? = null
}

/**
 * An object that was refused but kept: the bytes move to a quarantine key so they can never
 * be served at their original URL, while an operator can still inspect, restore or delete
 * them. `original_key` is kept so a restore is exact.
 */
@Entity
@Table(name = "media_quarantine")
class MediaQuarantineEntity : BaseEntity() {

    @Column(name = "upload_id")
    var uploadId: UUID? = null

    @Column(name = "original_key", nullable = false, length = 120)
    var originalKey: String = ""

    @Column(name = "quarantine_key", nullable = false, length = 160)
    var quarantineKey: String = ""

    @Column(name = "owner_id")
    var ownerId: UUID? = null

    /** INFECTED, SCAN_ERROR or POLICY. */
    @Column(nullable = false, length = 32)
    var reason: String = "INFECTED"

    @Column(length = 255)
    var detail: String? = null

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    @Column(name = "content_type", length = 128)
    var contentType: String? = null

    /** QUARANTINED, RESTORED or DELETED. */
    @Column(nullable = false, length = 16)
    var status: String = "QUARANTINED"

    @Column(name = "resolved_by")
    var resolvedBy: UUID? = null

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null
}
