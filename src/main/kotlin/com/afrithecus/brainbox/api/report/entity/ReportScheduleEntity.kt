package com.afrithecus.brainbox.api.report.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A recurring report schedule. Server-authoritative (nextRunAt/lastRunAt) and
 * scoped to its owner; clientId is the idempotency key the client sends so a
 * replayed create/delete is safe (docs/ongoing/api_reports_changes.md section 4).
 */
@Entity
@Table(name = "report_schedules")
class ReportScheduleEntity : BaseEntity() {

    @Column(name = "client_id", nullable = false, length = 80)
    var clientId: String = ""

    @Column(name = "owner_id", nullable = false)
    var ownerId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(nullable = false, length = 200)
    var title: String = ""

    @Column(name = "report_type", nullable = false, length = 64)
    var reportType: String = ""

    @Column(name = "class_id", length = 80)
    var classId: String? = null

    @Column(length = 64)
    var term: String? = null

    @Column(nullable = false, length = 24)
    var frequency: String = ""

    @Column(nullable = false, length = 120)
    var destination: String = ""

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(name = "next_run_at", nullable = false)
    var nextRunAt: Instant = Instant.now()

    @Column(name = "last_run_at")
    var lastRunAt: Instant? = null
}
