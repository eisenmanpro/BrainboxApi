package com.afrithecus.brainbox.api.cbc.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Unique view tracking (one view per user per project). */
@Entity
@Table(name = "cbc_project_views")
class CbcProjectViewEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "project_id", nullable = false)
    var projectId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(name = "viewed_at", nullable = false, updatable = false)
    var viewedAt: Instant = Instant.now()
}
