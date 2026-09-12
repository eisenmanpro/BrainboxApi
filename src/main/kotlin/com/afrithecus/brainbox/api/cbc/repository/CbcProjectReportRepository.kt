package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectReportEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectReportRepository : JpaRepository<CbcProjectReportEntity, UUID> {

    fun findByProjectIdAndGuestIdAndReason(projectId: UUID, guestId: String, reason: String): CbcProjectReportEntity?
}
