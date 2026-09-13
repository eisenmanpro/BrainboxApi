package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.AuditLogEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuditLogRepository : JpaRepository<AuditLogEntity, UUID> {

    fun findAllBySchoolIdOrderByCreatedAtDesc(schoolId: UUID, pageable: Pageable): List<AuditLogEntity>
}
