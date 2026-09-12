package com.afrithecus.brainbox.api.cbc.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** An email subscription to a project's updates (public "Track"). */
@Entity
@Table(name = "cbc_project_tracks")
class CbcProjectTrackEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "project_id", nullable = false)
    var projectId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 200)
    var email: String = ""

    @Column(name = "guest_id", length = 64)
    var guestId: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
