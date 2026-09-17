package com.afrithecus.brainbox.api.report

import com.afrithecus.brainbox.api.report.repository.ReportDownloadRepository
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import com.afrithecus.brainbox.api.report.repository.ReportScheduleRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Background maintenance: runs due report schedules (so the client's daily worker
 * sees lastRunAt advance and raises the "ready" notice) and prunes downloads
 * outside the quota window plus report files past the retention window.
 */
@Component
class ReportScheduler(
    private val schedules: ReportScheduleRepository,
    private val generation: ReportGenerationService,
    private val scheduleService: ReportScheduleService,
    private val downloads: ReportDownloadRepository,
    private val jobs: ReportJobRepository,
    private val storage: ReportStorage,
    private val properties: ReportProperties,
    private val clock: Clock,
) {

    @Scheduled(initialDelay = 120_000, fixedDelay = 900_000)
    fun runDueSchedules() {
        val now = clock.instant()
        schedules.findAllByEnabledTrueAndNextRunAtLessThanEqual(now).forEach { schedule ->
            runCatching { generation.generateForSchedule(schedule) }
            runCatching { scheduleService.advance(schedule.id, now) }
        }
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 86_400_000)
    fun prune() {
        val retention = Duration.ofDays(properties.retentionDays.toLong())
        downloads.deleteByDownloadedAtBefore(clock.instant().minus(Duration.ofDays(DOWNLOAD_HISTORY_DAYS)))
        // Expire job rows with their files, so a READY history entry cannot outlive
        // the file it points at. Download rows cascade.
        jobs.deleteByCreatedAtBefore(clock.instant().minus(retention))
        storage.deleteOlderThan(retention.seconds)
    }

    private companion object {
        const val DOWNLOAD_HISTORY_DAYS = 56L
    }
}
