package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.identity.entity.AuditLogEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.repository.AuditLogRepository
import com.afrithecus.brainbox.api.identity.web.AuditLogEntryPayload
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Server-owned administrative audit trail (docs/ongoing/api_admin_changes.md).
 * Paged newest-first with a before cursor; entries are pruned after RETENTION_DAYS.
 */
@Service
class AuditLogService(
    private val repository: AuditLogRepository,
    private val clock: Clock,
) {

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

    /**
     * A platform console action. It has no school, and it records what was acted on and why,
     * so an operator's actions can be reconstructed without the request body (which may hold
     * a token or a URL).
     */
    @Transactional
    fun recordPlatform(actor: UserEntity?, action: String, target: String? = null, detail: String? = null) {
        repository.save(
            AuditLogEntity().apply {
                schoolId = null
                actorId = actor?.id
                actorName = actor?.name ?: "System"
                this.action = action
                this.target = target?.take(200)
                this.detail = detail?.take(500)
            }
        )
    }

    /** Platform (school-less) entries, newest first, for the console's audit view. */
    @Transactional(readOnly = true)
    fun listPlatform(limit: Int, before: Instant?): List<AuditLogEntryPayload> {
        val page = PageRequest.of(0, limit.coerceIn(1, 200))
        val rows = if (before == null) {
            repository.findAllBySchoolIdIsNullOrderByCreatedAtDesc(page)
        } else {
            repository.findAllBySchoolIdIsNullAndCreatedAtLessThanOrderByCreatedAtDesc(before, page)
        }
        return rows.map {
            AuditLogEntryPayload(
                logId = it.id.toString(),
                actorName = it.actorName,
                action = it.action,
                timestamp = it.createdAt.toEpochMilli(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun list(schoolId: UUID, limit: Int, before: Instant?): List<AuditLogEntryPayload> {
        val page = PageRequest.of(0, limit.coerceIn(1, 200))
        val rows = if (before == null) {
            repository.findAllBySchoolIdOrderByCreatedAtDesc(schoolId, page)
        } else {
            repository.findAllBySchoolIdAndCreatedAtLessThanOrderByCreatedAtDesc(schoolId, before, page)
        }
        return rows.map {
            AuditLogEntryPayload(
                logId = it.id.toString(),
                actorName = it.actorName,
                action = it.action,
                timestamp = it.createdAt.toEpochMilli(),
            )
        }
    }

    @Transactional
    fun prune() {
        repository.deleteByCreatedAtBefore(clock.instant().minus(Duration.ofDays(RETENTION_DAYS)))
    }

    companion object {
        /** Documented retention window the client prunes its Room cache to. */
        const val RETENTION_DAYS = 180L
    }
}
