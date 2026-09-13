package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** A school-scoped administrative action, server-owned (api_admin_changes.md). */
@Entity
@Table(name = "audit_logs")
class AuditLogEntity : BaseEntity() {

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "actor_id")
    var actorId: UUID? = null

    @Column(name = "actor_name", nullable = false, length = 160)
    var actorName: String = ""

    @Column(nullable = false, length = 200)
    var action: String = ""
}
