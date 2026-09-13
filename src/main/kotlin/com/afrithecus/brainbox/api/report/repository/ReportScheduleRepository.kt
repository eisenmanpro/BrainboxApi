package com.afrithecus.brainbox.api.report.repository

import com.afrithecus.brainbox.api.report.entity.ReportScheduleEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ReportScheduleRepository : JpaRepository<ReportScheduleEntity, UUID> {

    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: UUID): List<ReportScheduleEntity>

    fun findByOwnerIdAndClientId(ownerId: UUID, clientId: String): ReportScheduleEntity?

    fun findAllByEnabledTrueAndNextRunAtLessThanEqual(now: Instant): List<ReportScheduleEntity>
}
