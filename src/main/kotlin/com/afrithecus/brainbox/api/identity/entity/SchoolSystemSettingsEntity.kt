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

    /** Grade coordinator's switch for learner grade/stream transitions (item 4). */
    @Column(name = "transitions_enabled", nullable = false)
    var transitionsEnabled: Boolean = true

    /**
     * N4: may school administrators (role TEACHER + sub_role ICT_ADMIN) sign in?
     * Open by default. When false, the pinned first administrator is exempt so the
     * switch can always be turned back off.
     */
    @Column(name = "admin_sign_in_open", nullable = false)
    var adminSignInOpen: Boolean = true

    /** N4: the school's first administrator, pinned the first time it is needed. */
    @Column(name = "admin_owner_id")
    var adminOwnerId: java.util.UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
