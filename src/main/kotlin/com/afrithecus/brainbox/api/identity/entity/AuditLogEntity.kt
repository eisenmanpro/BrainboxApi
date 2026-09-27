package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * An administrative action, server-owned (api_admin_changes.md). A school-scoped row carries
 * its school; a platform console action has no school and leaves [schoolId] null.
 */
@Entity
@Table(name = "audit_logs")
class AuditLogEntity : BaseEntity() {

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "actor_id")
    var actorId: UUID? = null

    @Column(name = "actor_name", nullable = false, length = 160)
    var actorName: String = ""

    @Column(nullable = false, length = 200)
    var action: String = ""

    /** What the action was applied to, e.g. `settings` or `quarantine:<id>`. */
    @Column(length = 200)
    var target: String? = null

    /** The change or reason, enough to reconstruct what happened without the request body. */
    @Column(length = 500)
    var detail: String? = null
}
