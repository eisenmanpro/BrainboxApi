package com.afrithecus.brainbox.api.cbc.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A moderation report submitted from the public project flow. */
@Entity
@Table(name = "cbc_project_reports")
class CbcProjectReportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "project_id", nullable = false)
    var projectId: UUID = UUID.randomUUID()

    @Column(name = "guest_id", nullable = false, length = 64)
    var guestId: String = ""

    @Column(nullable = false, length = 64)
    var reason: String = ""

    @Column(columnDefinition = "text")
    var details: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
