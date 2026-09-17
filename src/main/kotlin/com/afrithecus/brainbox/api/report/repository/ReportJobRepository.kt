package com.afrithecus.brainbox.api.report.repository

import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ReportJobRepository : JpaRepository<ReportJobEntity, UUID> {

    /**
     * Retention sweep: drop job rows past the reporting window. Their download
     * ledger rows cascade (report_downloads.job_id ON DELETE CASCADE) and the
     * rendered files are removed by the storage sweep, so an expired history entry
     * cannot outlive its file. Derived delete queries need an active transaction.
     */
    fun findAllByCreatedAtBefore(cutoff: Instant): List<ReportJobEntity>

    @Transactional
    fun deleteByCreatedAtBefore(cutoff: Instant)

    fun findByOwnerIdAndRequestId(ownerId: UUID, requestId: String): ReportJobEntity?

    fun findAllByOwnerIdAndStatusOrderByCreatedAtDesc(
        ownerId: UUID,
        status: String,
        pageable: Pageable,
    ): List<ReportJobEntity>

    fun findAllByOwnerIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
        ownerId: UUID,
        status: String,
        before: Instant,
        pageable: Pageable,
    ): List<ReportJobEntity>

    fun findAllByStatusIn(statuses: Collection<String>): List<ReportJobEntity>
}
