package com.afrithecus.brainbox.api.common.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Cached response for a replayed idempotent request (V5). */
@Entity
@Table(name = "idempotency_records")
class IdempotencyRecordEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "key_hash", nullable = false, length = 64)
    var keyHash: String = ""

    @Column(nullable = false, length = 8)
    var method: String = ""

    @Column(nullable = false, length = 255)
    var path: String = ""

    @Column(nullable = false)
    var status: Int = 200

    @Column(name = "content_type", length = 128)
    var contentType: String? = null

    @Column(columnDefinition = "text")
    var body: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant = Instant.now()
}
