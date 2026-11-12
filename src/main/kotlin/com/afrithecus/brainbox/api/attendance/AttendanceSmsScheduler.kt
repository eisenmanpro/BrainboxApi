package com.afrithecus.brainbox.api.attendance

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * P3.4: the SMS sweep exists only while the channel is enabled. It runs after register close by
 * policy, not by scheduler trickery, and every failure is swallowed here: a provider outage must
 * never surface to a teacher or another scheduled job.
 */
@Component
@ConditionalOnProperty(prefix = "app.attendance.sms", name = ["enabled"], havingValue = "true")
class AttendanceSmsScheduler(
    private val dispatchService: AttendanceSmsDispatchService,
) {

    @Scheduled(
        initialDelayString = "\${app.attendance.sms.scan-initial-delay-ms:600000}",
        fixedDelayString = "\${app.attendance.sms.scan-ms:600000}",
    )
    fun sweep() {
        val attempted = runCatching { dispatchService.dispatchDue() }.getOrElse {
            log.warn("Attendance SMS sweep failed", it)
            return
        }
        if (attempted > 0) log.info("Attendance SMS sweep attempted {} message(s)", attempted)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AttendanceSmsScheduler::class.java)
    }
}
