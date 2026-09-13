package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import com.afrithecus.brainbox.api.report.web.ReportGenerationRequestPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Transactional job state transitions. Kept as its own bean so the renderer runs
 * outside any database transaction and the async runner can poll cancel state.
 */
@Service
class ReportJobStateService(
    private val jobs: ReportJobRepository,
    private val clock: Clock,
) {

    @Transactional
    fun create(
        ownerId: UUID,
        schoolId: UUID?,
        request: ReportGenerationRequestPayload,
        requestId: String,
        title: String,
        payloadJson: String,
    ): ReportJobEntity = jobs.saveAndFlush(
        ReportJobEntity().apply {
            this.ownerId = ownerId
            this.schoolId = schoolId
            this.requestId = requestId
            reportType = request.reportType.name
            this.title = title
            status = QUEUED
            progress = 0
            message = "Queued"
            classId = request.classId
            examId = request.examId
            term = request.term
            year = request.year
            this.payloadJson = payloadJson
        }
    )

    @Transactional(readOnly = true)
    fun find(id: UUID): ReportJobEntity? = jobs.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun byRequest(ownerId: UUID, requestId: String): ReportJobEntity? =
        jobs.findByOwnerIdAndRequestId(ownerId, requestId)

    /** QUEUED -> IN_PROGRESS; returns null when terminal, already running or cancelled. */
    @Transactional
    fun markRunning(id: UUID): ReportJobEntity? {
        val job = jobs.findById(id).orElse(null) ?: return null
        if (job.status != QUEUED) return null
        if (job.cancelRequested) {
            terminal(job, "Cancelled")
            return null
        }
        job.status = IN_PROGRESS
        job.progress = maxOf(job.progress, 10)
        job.message = "Rendering"
        return jobs.saveAndFlush(job)
    }

    @Transactional(readOnly = true)
    fun isCancelRequested(id: UUID): Boolean = jobs.findById(id).orElse(null)?.cancelRequested ?: true

    @Transactional
    fun markReady(id: UUID, fileName: String, fileSize: Long, storageName: String): ReportJobEntity? {
        val job = jobs.findById(id).orElse(null) ?: return null
        if (job.cancelRequested) {
            terminal(job, "Cancelled")
            return null
        }
        job.status = READY
        job.progress = 100
        job.message = "Report ready"
        job.fileName = fileName
        job.fileSize = fileSize
        job.storageName = storageName
        job.failureReason = null
        job.completedAt = clock.instant()
        return jobs.saveAndFlush(job)
    }

    @Transactional
    fun markFailed(id: UUID, reason: String) {
        val job = jobs.findById(id).orElse(null) ?: return
        if (job.status == READY || job.status == FAILED) return
        val message = reason.take(500)
        job.status = FAILED
        job.message = message
        job.failureReason = message
        job.completedAt = clock.instant()
        jobs.saveAndFlush(job)
    }

    /** Repeat-safe: cancelling an already terminal job returns it unchanged. */
    @Transactional
    fun cancel(id: UUID): ReportJobEntity? {
        val job = jobs.findById(id).orElse(null) ?: return null
        if (job.status == READY || job.status == FAILED) return job
        job.cancelRequested = true
        terminal(job, "Cancelled")
        return job
    }

    private fun terminal(job: ReportJobEntity, message: String) {
        job.status = FAILED
        job.message = message
        job.failureReason = message
        job.completedAt = clock.instant()
        jobs.saveAndFlush(job)
    }

    private companion object {
        const val QUEUED = "QUEUED"
        const val IN_PROGRESS = "IN_PROGRESS"
        const val READY = "READY"
        const val FAILED = "FAILED"
    }
}
