package com.afrithecus.brainbox.api.achievements.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Badge catalog entry (doc 03 §7.1). */
@Entity
@Table(name = "badges")
class BadgeEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 128)
    var title: String = ""

    @Column(nullable = false, length = 64)
    var icon: String = ""

    @Column(nullable = false, length = 255)
    var description: String = ""

    @Column(length = 16)
    var tier: String? = null

    @Column(name = "cbc_strand", length = 32)
    var cbcStrand: String? = null

    @Column(name = "is_secret", nullable = false)
    var isSecret: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
