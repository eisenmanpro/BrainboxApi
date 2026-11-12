package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceSmsDispatchEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceSmsStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceSmsDispatchRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * P6.0: staging-only delivery-report simulation.
 *
 * The real HostPinnacle DLR payload is still unobserved, so staging needs a way to exercise the
 * webhook's persistence path, the ledger's delivery columns and the cost dashboards before the
 * purchased sender ID arrives. This simulator writes the same normalised report through
 * [AttendanceSmsDlrService.applyReport], so it cannot diverge from the production path.
 *
 * It is staging-only three times over:
 *  - the controller and scheduler beans are annotated `@Profile("staging")` and exist only there;
 *  - this service refuses every call unless the `staging` profile is active, even if the property
 *    is accidentally set in production.
 *
 * Manual calls also require the same shared secret as the provider webhook
 * (`ATTENDANCE_SMS_DLR_SECRET`), so a staging URL alone cannot mutate delivery state.
 */
@Service
class AttendanceSmsDlrSimulator(
    private val properties: AttendanceSmsProperties,
    private val dlrService: AttendanceSmsDlrService,
    private val dispatches: AttendanceSmsDispatchRepository,
    private val environment: Environment,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {

    enum class SimulationOutcome {
        SIMULATED,
        NOT_STAGING,
        DISABLED,
        UNAUTHORIZED,
        BAD_REQUEST,
        NOT_FOUND,
    }

    data class SimulationResult(
        val outcome: SimulationOutcome,
        val messageId: String? = null,
        val dispatchId: String? = null,
        val status: String? = null,
    )

    data class SweepResult(
        val outcome: SimulationOutcome,
        val simulated: Int = 0,
    )

    /**
     * Simulates one delivery report. The caller supplies exactly one identifier:
     *  - `dispatchId` - the ledger row id, usable even when the provider returned no message id
     *    (the logging gateway); or
     *  - `messageId` - the provider message id, matching a real provider delivery report.
     *
     * Returns [SimulationOutcome.UNAUTHORIZED] for a missing/wrong secret,
     * [SimulationOutcome.NOT_FOUND] for an unknown row, and [SimulationOutcome.BAD_REQUEST] for
     * anything other than DELIVERED/FAILED (or zero/two identifiers).
     */
    fun simulate(
        messageId: String?,
        dispatchId: String?,
        requestedStatus: String,
        secret: String?,
    ): SimulationResult {
        guard()?.let { return SimulationResult(it) }
        if (properties.dlrSecret.isBlank()) return SimulationResult(SimulationOutcome.DISABLED)
        if (!dlrService.secretAccepted(secret)) {
            return result(SimulationResult(SimulationOutcome.UNAUTHORIZED, messageId, dispatchId))
        }
        val byDispatch = dispatchId?.trim()?.takeIf { it.isNotEmpty() }
        val byMessage = messageId?.trim()?.takeIf { it.isNotEmpty() }
        if ((byDispatch == null) == (byMessage == null)) {
            return result(SimulationResult(SimulationOutcome.BAD_REQUEST, messageId, dispatchId))
        }
        val status = parseStatus(requestedStatus)
            ?: return result(SimulationResult(SimulationOutcome.BAD_REQUEST, messageId, dispatchId))
        val row = if (byDispatch != null) findDispatch(byDispatch) else findByMessage(byMessage!!)
        if (row == null) {
            return result(SimulationResult(SimulationOutcome.NOT_FOUND, messageId, dispatchId))
        }
        val outcome = when (dlrService.applyReport(row.id, status)) {
            AttendanceSmsDlrService.Outcome.ACCEPTED -> SimulationOutcome.SIMULATED
            else -> SimulationOutcome.NOT_FOUND
        }
        if (outcome == SimulationOutcome.SIMULATED) {
            meterRegistry.counter("brainbox.sms.dlr_simulated", "outcome", "manual").increment()
            log.info("SMS DLR simulated dispatch={} status={}", row.id, status)
        }
        return SimulationResult(outcome, row.providerMessageId, row.id.toString(), status.name)
    }

    /**
     * Marks up to [AttendanceSmsProperties.dlrSimulationLimit] pending SENT messages with the
     * configured outcome. Only messages older than `dlr-simulation-delay-seconds` are eligible,
     * so a manual targeted simulation can run before the automatic sweep touches the row. Rows
     * without a provider message id are included and addressed by dispatch id.
     */
    fun sweepPending(): SweepResult {
        guard()?.let { return SweepResult(it) }
        val status = parseStatus(properties.dlrSimulationOutcome)
            ?: AttendanceSmsDlrService.DlrStatus.DELIVERED
        val cutoff = clock.instant().minusSeconds(properties.dlrSimulationDelaySeconds.coerceAtLeast(0))
        val limit = properties.dlrSimulationLimit.coerceIn(1, MAX_SWEEP)
        val pending = dispatches.findPendingDlrSimulation(
            AttendanceSmsStatus.SENT,
            cutoff,
            PageRequest.of(0, limit),
        )
        var simulated = 0
        for (row in pending) {
            if (dlrService.applyReport(row.id, status) == AttendanceSmsDlrService.Outcome.ACCEPTED) {
                simulated++
            }
        }
        if (simulated > 0) {
            meterRegistry.counter("brainbox.sms.dlr_simulated", "outcome", "sweep").increment(simulated.toDouble())
            log.info("SMS DLR staging sweep simulated {} message(s) as {}", simulated, status)
        }
        return SweepResult(SimulationOutcome.SIMULATED, simulated)
    }

    private fun findDispatch(raw: String): AttendanceSmsDispatchEntity? =
        runCatching { dispatches.findById(UUID.fromString(raw)).orElse(null) }.getOrNull()

    private fun findByMessage(raw: String): AttendanceSmsDispatchEntity? =
        dispatches.findByProviderMessageId(raw)

    private fun guard(): SimulationOutcome? = when {
        !environment.acceptsProfiles(Profiles.of(STAGING_PROFILE)) -> SimulationOutcome.NOT_STAGING
        !properties.dlrSimulationEnabled -> SimulationOutcome.DISABLED
        else -> null
    }

    private fun result(value: SimulationResult): SimulationResult {
        meterRegistry.counter(
            "brainbox.sms.dlr_simulated",
            "outcome",
            value.outcome.name.lowercase(),
        ).increment()
        return value
    }

    private fun parseStatus(raw: String): AttendanceSmsDlrService.DlrStatus? = when (raw.trim().uppercase()) {
        "DELIVERED", "DELIVERY", "SUCCESS" -> AttendanceSmsDlrService.DlrStatus.DELIVERED
        "FAILED", "UNDELIVERED", "REJECTED" -> AttendanceSmsDlrService.DlrStatus.FAILED
        else -> null
    }

    private companion object {
        val log = LoggerFactory.getLogger(AttendanceSmsDlrSimulator::class.java)
        const val STAGING_PROFILE = "staging"
        const val MAX_SWEEP = 500
    }
}
