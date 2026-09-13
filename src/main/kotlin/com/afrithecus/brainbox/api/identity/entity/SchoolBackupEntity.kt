package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/** An explicit ICT-admin backup request for a school (api_admin_changes.md). */
@Entity
@Table(name = "school_backups")
class SchoolBackupEntity : BaseEntity() {

    @Column(name = "school_id", nullable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "requested_by")
    var requestedBy: UUID? = null

    @Column(nullable = false, length = 16)
    var status: String = "READY"

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0

    /** Logical JSON export of the school's core records (no credentials). */
    @Column(columnDefinition = "text")
    var payload: String? = null

    @Column(name = "file_name", length = 255)
    var fileName: String? = null

    /** SHA-256 of the payload, so a download can be verified. */
    @Column(length = 64)
    var sha256: String? = null
}
