package com.afrithecus.brainbox.api.identity.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * Per-school branding and academic configuration (client
 * repository/admin/SchoolAnalyticsRepository.kt SchoolConfig). Read by the
 * report renderer so a server-produced PDF carries the same header/watermark as
 * the on-device PdfGenerator, and edited by ICT admins at
 * admin/schools/{id}/config. List fields are stored as JSON.
 */
@Entity
@Table(name = "school_configs")
class SchoolConfigEntity {

    @Id
    @Column(name = "school_id", nullable = false, updatable = false)
    var schoolId: UUID = UUID.randomUUID()

    @Column(name = "school_name", nullable = false, length = 120)
    var schoolName: String = ""

    @Column(length = 160)
    var motto: String? = null

    @Column(name = "logo_url", length = 512)
    var logoUrl: String? = null

    @Column(name = "primary_color", length = 16)
    var primaryColor: String? = null

    @Column(length = 255)
    var address: String? = null

    @Column(length = 64)
    var phone: String? = null

    @Column(length = 200)
    var email: String? = null

    @Column(name = "watermark_text", length = 120)
    var watermarkText: String? = null

    @Column(name = "academic_calendar", columnDefinition = "text")
    var academicCalendar: String? = null

    @Column(name = "cbc_strands", columnDefinition = "text")
    var cbcStrands: String? = null

    @Column(columnDefinition = "text")
    var rooms: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
