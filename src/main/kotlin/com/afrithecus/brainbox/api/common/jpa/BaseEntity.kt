package com.afrithecus.brainbox.api.common.jpa

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Shared identity/timestamp/version columns.
 *
 * updated_at + version support optimistic locking and ETag/If-Match conflict
 * resolution for offline-first sync (doc 11 §8.4). createdAt is immutable.
 */
@MappedSuperclass
abstract class BaseEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0

    @PrePersist
    fun touchOnPersist() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun touchOnUpdate() {
        updatedAt = Instant.now()
    }
}
