package com.afrithecus.brainbox.api.report.web

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.report.ReportDownloadService
import com.afrithecus.brainbox.api.report.ReportGenerationService
import com.afrithecus.brainbox.api.report.ReportScheduleService
import com.afrithecus.brainbox.api.report.ReportStorage
import com.afrithecus.brainbox.api.report.repository.ReportJobRepository
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/**
 * Teacher/coordinator reporting hub (docs/ongoing/api_reports_changes.md). The
 * actor is always the bearer-token principal; downloads use a short-lived signed
 * token because the client fetches fileUrl without an Authorization header.
 */
@RestController
@RequestMapping("/teacher/reports")
class TeacherReportController(
    private val generation: ReportGenerationService,
    private val scheduleService: ReportScheduleService,
    private val downloads: ReportDownloadService,
    private val jobs: ReportJobRepository,
    private val storage: ReportStorage,
) {

    @PostMapping("/generate")
    fun generate(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: ReportGenerationRequestPayload,
    ): ReportJobPayload = generation.submit(current, request, baseUrl())

    @PostMapping("/bulk")
    fun bulk(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: ReportGenerationRequestPayload,
    ): ReportJobPayload = generation.submit(current, request, baseUrl())

    @GetMapping("/job/{jobId}")
    fun job(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable jobId: String,
    ): ReportJobPayload = generation.get(current, jobId, baseUrl())

    @PostMapping("/job/{jobId}/cancel")
    fun cancel(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable jobId: String,
    ): ReportJobPayload = generation.cancel(current, jobId, baseUrl())

    @GetMapping("/history")
    fun history(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) before: Long?,
    ): List<ReportHistoryItemPayload> = generation.history(current, limit, before, baseUrl())

    @GetMapping("/quota")
    fun quota(@AuthenticationPrincipal current: CurrentUser): ReportQuotaPayload = generation.quota(current)

    @GetMapping("/schedules")
    fun schedules(@AuthenticationPrincipal current: CurrentUser): List<ReportSchedulePayload> =
        scheduleService.list(current)

    @PostMapping("/schedules")
    fun createSchedule(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: ReportSchedulePayload,
    ): ReportSchedulePayload = scheduleService.create(current, request)

    @PutMapping("/schedules/{scheduleId}")
    fun updateSchedule(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable scheduleId: String,
        @RequestBody request: ReportSchedulePayload,
    ): ReportSchedulePayload = scheduleService.update(current, scheduleId, request)

    @DeleteMapping("/schedules/{scheduleId}")
    fun deleteSchedule(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable scheduleId: String,
    ): ResponseEntity<Void> {
        scheduleService.delete(current, scheduleId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Signed download. Permitted without a bearer header (the client uses a plain
     * HTTP fetch); the token binds the job id to a short expiry and is verified by
     * HMAC. Counts against the owner's weekly quota.
     */
    @GetMapping("/download/{jobId}")
    fun download(
        @PathVariable jobId: String,
        @RequestParam(required = false) token: String?,
    ): ResponseEntity<ByteArray> {
        val id = runCatching { UUID.fromString(jobId) }.getOrNull() ?: throw notFound("Report not found")
        if (!downloads.verify(id, token)) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Invalid or expired download link")
        }
        val job = jobs.findById(id).orElse(null) ?: throw notFound("Report not found")
        val storageName = job.storageName
        if (job.status != "READY" || storageName == null) throw notFound("Report file is not ready")
        val quota = downloads.quota(job.ownerId)
        if (quota.remaining <= 0) {
            throw ApiException(ApiErrorCode.TOO_MANY_REQUESTS, "Weekly report export limit reached")
        }
        val bytes = storage.read(storageName) ?: throw notFound("Report file is not available")
        downloads.record(job.ownerId, id)
        val fileName = (job.fileName ?: (id.toString() + ".pdf")).replace("\"", "")
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .header("X-Reports-Quota-Remaining", (quota.remaining - 1).coerceAtLeast(0).toString())
            .body(bytes)
    }

    private fun baseUrl(): String = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
}
