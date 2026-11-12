package com.afrithecus.brainbox.api.attendance.web

import com.afrithecus.brainbox.api.attendance.AttendanceSmsDlrSimulator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * P6.0: one simulated delivery report. Supply exactly one identifier: [dispatchId] (the ledger row
 * id, which exists even when the provider returned no message id) or [messageId] (the provider
 * message id, matching a real DLR). Status is DELIVERED or FAILED, case-insensitive.
 */
data class SimulateDlrRequest(
    val dispatchId: String? = null,
    val messageId: String? = null,
    val status: String = "DELIVERED",
)

data class SimulateDlrResponse(
    val outcome: String,
    val messageId: String? = null,
    val dispatchId: String? = null,
    val status: String? = null,
)

/**
 * P6.0: staging-only DLR simulator endpoint.
 *
 * The bean exists only under the `staging` profile and only while
 * `app.attendance.sms.dlr-simulation-enabled=true`, so production has no route at all. It is
 * public in the security chain for the same reason as the provider webhook (the caller presents
 * the shared secret, not a bearer token) and authenticates every request with
 * `X-SMS-DLR-Secret`. Automatic delivery simulation is done by
 * [AttendanceSmsDlrSimulationScheduler]; this endpoint is for targeted evidence in a smoke test.
 */
@RestController
@Profile("staging")
@ConditionalOnProperty(prefix = "app.attendance.sms", name = ["dlr-simulation-enabled"], havingValue = "true")
class AttendanceSmsDlrSimulationController(
    private val simulator: AttendanceSmsDlrSimulator,
) {

    @PostMapping("/webhooks/sms/dlr/simulate")
    fun simulate(
        @RequestHeader(name = "X-SMS-DLR-Secret", required = false) secret: String?,
        @RequestBody request: SimulateDlrRequest,
    ): ResponseEntity<SimulateDlrResponse> {
        val result = simulator.simulate(request.messageId, request.dispatchId, request.status, secret)
        val response = SimulateDlrResponse(
            result.outcome.name,
            result.messageId,
            result.dispatchId,
            result.status,
        )
        val code = when (result.outcome) {
            AttendanceSmsDlrSimulator.SimulationOutcome.SIMULATED -> 200
            AttendanceSmsDlrSimulator.SimulationOutcome.UNAUTHORIZED -> 401
            AttendanceSmsDlrSimulator.SimulationOutcome.BAD_REQUEST -> 400
            AttendanceSmsDlrSimulator.SimulationOutcome.NOT_FOUND -> 404
            AttendanceSmsDlrSimulator.SimulationOutcome.DISABLED -> 404
            AttendanceSmsDlrSimulator.SimulationOutcome.NOT_STAGING -> 404
        }
        return ResponseEntity.status(code).body(response)
    }
}
