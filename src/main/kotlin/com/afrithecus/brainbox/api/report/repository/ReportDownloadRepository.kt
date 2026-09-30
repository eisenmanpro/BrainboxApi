package com.afrithecus.brainbox.api.report.repository

import com.afrithecus.brainbox.api.report.entity.ReportDownloadEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface ReportDownloadRepository : JpaRepository<ReportDownloadEntity, UUID> {

    fun countByOwnerIdAndDownloadedAtGreaterThanEqual(ownerId: UUID, since: Instant): Long

    /** Lifetime per-student units consumed — the metered allowance (item 1). */
    @Query(
        "select coalesce(sum(d.studentCount), 0) from ReportDownloadEntity d " +
            "where d.ownerId = :ownerId and d.scope = 'STUDENT'"
    )
    fun sumStudentUnits(@Param("ownerId") ownerId: UUID): Long

    // Derived delete queries need an active transaction; the scheduler prune and
    // the test cleanup both call these outside one.
    @Transactional
    fun deleteByDownloadedAtBefore(cutoff: Instant)

    @Transactional
    fun deleteByOwnerId(ownerId: UUID)
}
