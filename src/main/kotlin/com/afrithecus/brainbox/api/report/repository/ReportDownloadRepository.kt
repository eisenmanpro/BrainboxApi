package com.afrithecus.brainbox.api.report.repository

import com.afrithecus.brainbox.api.report.entity.ReportDownloadEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ReportDownloadRepository : JpaRepository<ReportDownloadEntity, UUID> {

    fun countByOwnerIdAndDownloadedAtGreaterThanEqual(ownerId: UUID, since: Instant): Long

    fun deleteByDownloadedAtBefore(cutoff: Instant)
}
