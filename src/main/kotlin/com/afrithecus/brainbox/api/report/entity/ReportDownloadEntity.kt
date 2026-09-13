package com.afrithecus.brainbox.api.report.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Server-authoritative export-quota ledger: one row per signed report download
 * (docs/ongoing/api_reports_changes.md section 3).
 */
@Entity
@Table(name = "report_downloads")
class ReportDownloadEntity {

    @Id
    @Column(nullable = false, updatable = false)
    var id: UUID = UUID.randomUUID()

    @Column(name = "owner_id", nullable = false)
    var ownerId: UUID = UUID.randomUUID()

    @Column(name = "job_id", nullable = false)
    var jobId: UUID = UUID.randomUUID()

    @Column(name = "downloaded_at", nullable = false, updatable = false)
    var downloadedAt: Instant = Instant.now()
}
