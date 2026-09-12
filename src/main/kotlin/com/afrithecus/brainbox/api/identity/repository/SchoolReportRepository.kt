package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolReportEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolReportRepository : JpaRepository<SchoolReportEntity, UUID> {

    fun findBySchoolIdAndUserIdAndReason(schoolId: UUID, userId: UUID, reason: String): SchoolReportEntity?
}
