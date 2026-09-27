package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * A console role: a named set of [PlatformPermission]s an operator hands to an account.
 *
 * Roles are how the console stays manageable as it grows — "Operations", "Security officer",
 * "Support (read only)" are capability sets, not separate code paths. A **system** role is
 * seeded and cannot be renamed or deleted (only its capabilities are editable), so there is
 * always a way back into the console.
 */
@Entity
@Table(name = "console_roles")
class ConsoleRoleEntity : BaseEntity() {

    @Column(nullable = false, unique = true, length = 64)
    var name: String = ""

    @Column(nullable = false, length = 255)
    var description: String = ""

    /** The stored capability list; read through [PlatformPermission.parse]. */
    @Column(nullable = false, length = 512)
    var capabilities: String = ""

    @Column(name = "is_system", nullable = false)
    var isSystem: Boolean = false

    @Column(name = "created_by")
    var createdBy: UUID? = null
}
