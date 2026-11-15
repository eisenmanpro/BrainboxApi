package com.afrithecus.brainbox.api.media.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A proxied upload whose URL is saved by a SECOND request (currently school
 * branding). The object is written immediately, so without this row an upload
 * the admin abandons would stay in the store forever; [referencedAt] is set by
 * the write that actually persists the URL, and the scheduler deletes rows that
 * were never claimed after the grace period.
 */
@Entity
@Table(name = "media_objects")
class MediaObjectEntity {

    /** The server-chosen object key, e.g. `uuid.png`. */
    @Id
    @Column(name = "storage_key", nullable = false, length = 120)
    var storageKey: String = ""

    /** Why the object exists (BRANDING today); a future lifecycle can reuse the table. */
    @Column(nullable = false, length = 32)
    var purpose: String = ""

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    /** Set once a persisted record points at this object; null means still unreferenced. */
    @Column(name = "referenced_at")
    var referencedAt: Instant? = null
}
