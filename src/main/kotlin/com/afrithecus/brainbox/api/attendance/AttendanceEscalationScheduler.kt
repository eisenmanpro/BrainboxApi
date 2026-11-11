package com.afrithecus.brainbox.api.attendance

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * F8-A2: absence escalation sweep. Runs often enough to notice the configured
 * school-time threshold (default 10:00) without a second notification for the
 * same record. [AttendanceService.escalateAbsentees] is idempotent.
 */
@Component
class AttendanceEscalationScheduler(
    private val attendanceService: AttendanceService,
) {

    @Scheduled(
        initialDelayString = "\${app.attendance.escalation-scan-initial-delay-ms:300000}",
        fixedDelayString = "\${app.attendance.escalation-scan-ms:900000}",
    )
    fun sweep() {
        val sent = runCatching { attendanceService.escalateAbsentees() }.getOrElse {
            log.warn("Attendance escalation sweep failed", it)
            return
        }
        if (sent > 0) log.info("Attendance escalation sent {} reminder(s)", sent)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AttendanceEscalationScheduler::class.java)
    }
}
