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

    /** MEDIA, HOMEWORK_ATTACHMENT or DOCUMENT. */
    @Column(nullable = false, length = 32)
    var purpose: String = ""

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()
}
