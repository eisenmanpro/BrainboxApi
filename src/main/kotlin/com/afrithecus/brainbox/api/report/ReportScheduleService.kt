package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.report.entity.ReportScheduleEntity
import com.afrithecus.brainbox.api.report.repository.ReportScheduleRepository
import com.afrithecus.brainbox.api.report.web.ReportSchedulePayload
import com.afrithecus.brainbox.api.report.web.ReportType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Server-authoritative report schedules (docs/ongoing/api_reports_changes.md
 * section 4). Scoped to the caller; create is idempotent per client id and delete
 * is repeat-safe so the offline-first client can replay them.
 */
@Service
class ReportScheduleService(
    private val repository: ReportScheduleRepository,
    private val clock: Clock,
    @Value("\${app.school-zone:Africa/Nairobi}") private val schoolZone: String,
) {

    @Transactional(readOnly = true)
    fun list(actor: CurrentUser): List<ReportSchedulePayload> =
        repository.findAllByOwnerIdOrderByCreatedAtDesc(actor.userId).map(::payload)

    @Transactional
    fun create(actor: CurrentUser, request: ReportSchedulePayload): ReportSchedulePayload {
        val frequency = validateFrequency(request.frequency)
        val title = request.title.trim().ifBlank { throw invalidArgument("title is required") }
        val clientId = request.id.trim().ifBlank { UUID.randomUUID().toString() }
        repository.findByOwnerIdAndClientId(actor.userId, clientId)?.let { return payload(it) }
        val now = clock.instant()
        val entity = ReportScheduleEntity().apply {
            ownerId = actor.userId
            this.clientId = clientId
            this.title = title
            reportType = request.reportType.name
            classId = request.classId
            term = request.term
            this.frequency = frequency
            destination = request.destination.ifBlank { "Device" }
            enabled = request.enabled
            nextRunAt = request.nextRunAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: nextRun(now, frequency)
        }
        repository.saveAndFlush(entity)
        return payload(entity)
    }

    @Transactional
    fun update(actor: CurrentUser, scheduleIdRaw: String, request: ReportSchedulePayload): ReportSchedulePayload {
        val entity = resolve(actor.userId, scheduleIdRaw) ?: throw notFound("Schedule not found")
        val frequency = validateFrequency(request.frequency)
        entity.title = request.title.trim().ifBlank { entity.title }
        entity.reportType = request.reportType.name
        entity.classId = request.classId
        entity.term = request.term
        entity.frequency = frequency
        entity.destination = request.destination.ifBlank { entity.destination }
        entity.enabled = request.enabled
        if (request.nextRunAt > 0) entity.nextRunAt = Instant.ofEpochMilli(request.nextRunAt)
        repository.saveAndFlush(entity)
        return payload(entity)
    }

    /** Repeat-safe: an unknown or already-deleted schedule is treated as deleted. */
    @Transactional
    fun delete(actor: CurrentUser, scheduleIdRaw: String) {
        resolve(actor.userId, scheduleIdRaw)?.let { repository.delete(it) }
    }

    /** Advances a schedule after a run and records when it last produced a report. */
    @Transactional
    fun advance(scheduleId: UUID, runAt: Instant) {
        val entity = repository.findById(scheduleId).orElse(null) ?: return
        entity.lastRunAt = runAt
        entity.nextRunAt = nextRun(runAt, entity.frequency)
        repository.saveAndFlush(entity)
    }

    // ------------------------------------------------------------ internals

    private fun resolve(ownerId: UUID, raw: String): ReportScheduleEntity? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        runCatching { UUID.fromString(value) }.getOrNull()?.let { id ->
            val entity = repository.findById(id).orElse(null)
            if (entity != null && entity.ownerId == ownerId) return entity
        }
        return repository.findByOwnerIdAndClientId(ownerId, value)
    }

    private fun validateFrequency(raw: String): String {
        val value = raw.trim().uppercase()
        if (value !in FREQUENCIES) throw invalidArgument("frequency must be one of " + FREQUENCIES.joinToString(", "))
        return value
    }

    private fun nextRun(now: Instant, frequency: String): Instant {
        val zone = runCatching { ZoneId.of(schoolZone) }.getOrDefault(ZoneId.of("Africa/Nairobi"))
        return when (frequency) {
            "ONCE" -> now.plus(Duration.ofDays(1))
            "WEEKLY" -> now.plus(Duration.ofDays(7))
            "MONTHLY" -> ZonedDateTime.ofInstant(now, zone).plusMonths(1).toInstant()
            else -> now.plus(Duration.ofDays(90))
        }
    }

    private fun payload(entity: ReportScheduleEntity) = ReportSchedulePayload(
        id = entity.id.toString(),
        title = entity.title,
        reportType = ReportType.valueOf(entity.reportType),
        classId = entity.classId,
        term = entity.term.orEmpty(),
        frequency = entity.frequency,
        destination = entity.destination,
        enabled = entity.enabled,
        nextRunAt = entity.nextRunAt.toEpochMilli(),
        lastRunAt = entity.lastRunAt?.toEpochMilli(),
        createdAt = entity.createdAt.toEpochMilli(),
    )

    private companion object {
        val FREQUENCIES = setOf("ONCE", "WEEKLY", "MONTHLY", "TERMLY")
    }
}
