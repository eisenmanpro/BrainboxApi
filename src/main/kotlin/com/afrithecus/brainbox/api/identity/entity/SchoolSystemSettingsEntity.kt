package com.afrithecus.brainbox.api.identity.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/** Per-school operational switches (docs/ongoing/api_admin_changes.md). */
@Entity
@Table(name = "school_system_settings")
class SchoolSystemSettingsEntity {

    @Id
    @Column(name = "school_id", nullable = false, updatable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "maintenance_mode", nullable = false)
    var maintenanceMode: Boolean = false

    @Column(name = "registration_open", nullable = false)
    var registrationOpen: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
