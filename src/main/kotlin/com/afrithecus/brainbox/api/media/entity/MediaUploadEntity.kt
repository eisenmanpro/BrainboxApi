package com.afrithecus.brainbox.api.media.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One presigned direct-upload ticket. A PENDING row proves the API issued the
 * key, so confirm can verify the object's real format before it is served, and
 * the sweeper can delete an abandoned or rejected object.
 */
@Entity
@Table(name = "media_uploads")
class MediaUploadEntity : BaseEntity() {

    @Column(name = "owner_id", nullable = false)
    var ownerId: UUID = UUID.randomUUID()

    /** The server-chosen object key, including the declared type's extension. */
    @Column(name = "storage_key", nullable = false, length = 120)
    var storageKey: String = ""

    /** The MediaKind the client declared; confirm must detect the same kind. */
    @Column(name = "declared_kind", nullable = false, length = 16)
    var declaredKind: String = ""

    /** MEDIA, HOMEWORK_ATTACHMENT, CHAT_ATTACHMENT or DOCUMENT. */
    @Column(nullable = false, length = 32)
    var purpose: String = ""

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()

    /**
     * The malware-scan verdict: CLEAN, INFECTED, SKIPPED (no scanner configured) or
     * ERROR. Defaults to SKIPPED so a row written before scanning existed is honest
     * about never having been checked.
     */
    @Column(name = "scan_status", nullable = false, length = 16)
    var scanStatus: String = "SKIPPED"

    /** The matched signature, or why the scan could not run. */
    @Column(name = "scan_detail", length = 255)
    var scanDetail: String? = null

    /** The scanner that produced the verdict (none, clamav, http). */
    @Column(length = 16)
    var scanner: String? = null

    /** ACCEPTED, QUARANTINED, DELETED or REFUSED. */
    @Column(name = "scan_action", length = 16)
    var scanAction: String? = null

    /** The quarantine row, when a refusal kept the bytes instead of deleting them. */
    @Column(name = "quarantine_id")
    var quarantineId: UUID? = null
}
