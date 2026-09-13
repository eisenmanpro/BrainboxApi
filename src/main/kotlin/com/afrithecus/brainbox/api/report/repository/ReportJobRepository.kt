package com.afrithecus.brainbox.api.report.repository

import com.afrithecus.brainbox.api.report.entity.ReportJobEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ReportJobRepository : JpaRepository<ReportJobEntity, UUID> {

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
