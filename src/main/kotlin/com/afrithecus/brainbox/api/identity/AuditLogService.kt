package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.identity.entity.AuditLogEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.AuditLogRepository
import com.afrithecus.brainbox.api.identity.web.AuditLogEntryPayload
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Server-owned administrative audit trail (docs/ongoing/api_admin_changes.md). */
@Service
class AuditLogService(private val repository: AuditLogRepository) {

    @Transactional
    fun record(schoolId: UUID, actor: UserEntity?, action: String) {
        repository.save(
            AuditLogEntity().apply {
                this.schoolId = schoolId
                actorId = actor?.id
                actorName = actor?.name ?: "System"
                this.action = action
            }
        )
    }

    @Transactional(readOnly = true)
    fun list(schoolId: UUID, limit: Int): List<AuditLogEntryPayload> =
        repository.findAllBySchoolIdOrderByCreatedAtDesc(schoolId, PageRequest.of(0, limit.coerceIn(1, 200)))
            .map {
                AuditLogEntryPayload(
                    logId = it.id.toString(),
                    actorName = it.actorName,
                    action = it.action,
                    timestamp = it.createdAt.toEpochMilli(),
                )
            }
}
