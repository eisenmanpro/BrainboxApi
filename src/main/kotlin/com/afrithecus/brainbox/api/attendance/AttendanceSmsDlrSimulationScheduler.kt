package com.afrithecus.brainbox.api.attendance

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * P6.0: automatic staging delivery reports, so a smoke test can verify the ledger and dashboards
 * without the provider. Exists only under the `staging` profile and only while
 * `app.attendance.sms.dlr-simulation-enabled=true`; production never has this bean.
 *
 * The sweep reports SENT messages older than `dlr-simulation-delay-seconds` with
 * `dlr-simulation-outcome` (DELIVERED by default). A failure here is swallowed: a staging helper
 * must never break the application or the real scheduler.
 */
@Component
@Profile("staging")
@ConditionalOnProperty(prefix = "app.attendance.sms", name = ["dlr-simulation-enabled"], havingValue = "true")
class AttendanceSmsDlrSimulationScheduler(
    private val simulator: AttendanceSmsDlrSimulator,
) {

    @Scheduled(
        initialDelayString = "\${app.attendance.sms.dlr-simulation-initial-delay-ms:30000}",
        fixedDelayString = "\${app.attendance.sms.dlr-simulation-scan-ms:30000}",
    )
    fun sweep() {
        val result = runCatching { simulator.sweepPending() }.getOrElse {
            log.warn("SMS DLR staging simulation sweep failed", it)
            return
        }
        when (result.outcome) {
            AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED -> {
                if (result.simulated > 0) {
                    log.info("SMS DLR staging sweep simulated {} message(s)", result.simulated)
                }
            }
            AttendanceSmsDlrSimulator.SimulationOutcome.NOT_STAGING,
            AttendanceSmsDlrSimulator.SimulationOutcome.DISABLED,
            -> Unit
            else -> Unit
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AttendanceSmsDlrSimulationScheduler::class.java)
    }
}
