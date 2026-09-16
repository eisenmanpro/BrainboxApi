package com.afrithecus.brainbox.api.ops.web

import com.afrithecus.brainbox.api.ops.OpsAdminService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * O1 admin operations API. ADMIN only, matching the other admin surfaces. It is
 * the server contract for the future separate internal ops frontend: live values
 * from actuator/Micrometer plus the backend-owned history in ops_metric_rollup.
 * It is not a learner- or teacher-facing surface.
 */
@RestController
@RequestMapping("/admin/ops")
@PreAuthorize("hasRole('ADMIN')")
class OpsAdminController(private val service: OpsAdminService) {

    /** Live queue/coverage/budget/cost plus the latest rolled-up reason mix. */
    @GetMapping("/summary")
    fun summary(): OpsSummary = service.summary()

    /** Shelf coverage per subject x grade. */
    @GetMapping("/coverage")
    fun coverage(): OpsCoveragePayload = service.coverage()

    /**
     * The rolled-up series for one metric, optionally one dimension, in
     * [from, to) as epoch millis. Bounded to [OpsTimeseriesPayload.limit] points.
     */
    @GetMapping("/timeseries")
    fun timeseries(
        @RequestParam metric: String?,
        @RequestParam(required = false) dimension: String?,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
    ): OpsTimeseriesPayload = service.timeseries(metric, dimension, from, to)

    /** The sampled machine approvals a human should spot-check. */
    @GetMapping("/audit")
    fun audit(@RequestParam(required = false, defaultValue = "50") limit: Int): OpsAuditPayload =
        service.audit(limit)
}
