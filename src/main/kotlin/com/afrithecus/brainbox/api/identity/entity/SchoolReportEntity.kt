package com.afrithecus.brainbox.api.identity.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A moderation report on a school listing. */
@Entity
@Table(name = "school_reports")
class SchoolReportEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 64)
    var reason: String = ""

    @Column(columnDefinition = "text")
    var detail: String? = null

    @Column(nullable = false, length = 32)
    var reference: String = ""

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
}
