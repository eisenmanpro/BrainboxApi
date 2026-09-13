package com.afrithecus.brainbox.api.report.web

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.report.ReportGenerationService
import com.afrithecus.brainbox.api.report.StudentReportService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/** Learner/parent report exports; see docs/ongoing/pdf_generator_cleanup.md section 3. */
@RestController
@RequestMapping("/student/reports")
class StudentReportController(
    private val studentReports: StudentReportService,
    private val generation: ReportGenerationService,
) {

    @PostMapping("/generate")
    fun generate(
        @AuthenticationPrincipal current: CurrentUser,
        @RequestBody request: StudentReportRequestPayload,
    ): ReportJobPayload = studentReports.generate(current, request, baseUrl())

    /** The owner (learner/parent/teacher) polls the same job they submitted. */
    @GetMapping("/job/{jobId}")
    fun job(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable jobId: String,
    ): ReportJobPayload = generation.get(current, jobId, baseUrl())

    @GetMapping("/quota")
    fun quota(@AuthenticationPrincipal current: CurrentUser): ReportQuotaPayload = generation.quota(current)

    private fun baseUrl(): String = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
}
