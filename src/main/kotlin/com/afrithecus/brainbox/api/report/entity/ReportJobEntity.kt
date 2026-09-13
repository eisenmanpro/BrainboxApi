package com.afrithecus.brainbox.api.report.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An asynchronous report-generation job, polled by the client and reused as the
 * paged export history once READY (docs/ongoing/api_reports_changes.md).
 * requestId is the client idempotency key: (owner, requestId) is unique so a
 * WorkManager retry returns the existing job instead of creating a second one.
 */
@Entity
@Table(name = "report_jobs")
class ReportJobEntity : BaseEntity() {

    @Column(name = "request_id", nullable = false, length = 80)
    var requestId: String = ""

    @Column(name = "owner_id", nullable = false)
    var ownerId: UUID = UUID.randomUUID()

    @Column(name = "school_id")
    var schoolId: UUID? = null

    @Column(name = "report_type", nullable = false, length = 64)
    var reportType: String = ""

    @Column(length = 200)
    var title: String? = null

    @Column(nullable = false, length = 24)
    var status: String = "QUEUED"

    @Column(nullable = false)
    var progress: Int = 0

    @Column(length = 500)
    var message: String? = null

    @Column(name = "failure_reason", length = 500)
    var failureReason: String? = null

    @Column(name = "class_id", length = 80)
    var classId: String? = null

    @Column(name = "exam_id", length = 80)
    var examId: String? = null

    @Column(length = 64)
    var term: String? = null

    @Column(name = "report_year")
    var year: Int? = null

    @Column(name = "file_name", length = 255)
    var fileName: String? = null

    @Column(name = "file_size", nullable = false)
    var fileSize: Long = 0

    @Column(name = "storage_name", length = 255)
    var storageName: String? = null

    @Column(name = "poll_after_millis")
    var pollAfterMillis: Long? = null

    @Column(name = "cancel_requested", nullable = false)
    var cancelRequested: Boolean = false

    @Column(name = "payload_json", columnDefinition = "text")
    var payloadJson: String? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}
