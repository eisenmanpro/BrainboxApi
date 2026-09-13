package com.afrithecus.brainbox.api.identity.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A user's request to register a new school (moderation queue). The school is
 * created only when an admin approves, so unknown schools never appear in the
 * public directory or accept enrolments while pending.
 */
@Entity
@Table(name = "school_registration_requests")
class SchoolRegistrationRequestEntity : BaseEntity() {

    @Column(name = "request_id", nullable = false, length = 80)
    var requestId: String = ""

    @Column(name = "school_name", nullable = false, length = 160)
    var schoolName: String = ""

    @Column(length = 255)
    var address: String? = null

    @Column(name = "submitted_by")
    var submittedBy: UUID? = null

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = Instant.now()

    @Column(nullable = false, length = 16)
    var status: String = "PENDING"

    @Column(name = "reviewed_by")
    var reviewedBy: UUID? = null

    @Column(name = "reviewed_at")
    var reviewedAt: Instant? = null

    @Column(name = "review_note", length = 500)
    var reviewNote: String? = null
}
