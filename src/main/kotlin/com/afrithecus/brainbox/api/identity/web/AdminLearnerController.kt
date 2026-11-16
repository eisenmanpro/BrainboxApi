package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.AdminLearnerService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * School-scoped learner management for the ICT admin console (findings
 * "user management"). The school is a path resource; the service checks the
 * actor's token (ICT_ADMIN own school, platform ADMIN any school) before any read
 * or write. Learner rows live only inside their own school.
 */
@RestController
@RequestMapping("/admin/schools/{schoolId}/learners")
@PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
class AdminLearnerController(private val service: AdminLearnerService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) gradeLevel: String?,
        @RequestParam(required = false) classId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
    ): LearnerPagePayload = service.page(current, schoolId, q, gradeLevel, classId, status, page, size)

    /**
     * The same filtered rows as one CSV download. Deliberately a server export: the
     * client never assembles rows of its own, so the file always matches the filters.
     */
    @GetMapping("/export")
    fun export(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) gradeLevel: String?,
        @RequestParam(required = false) classId: String?,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<ByteArray> {
        val csv = service.csv(current, schoolId, q, gradeLevel, classId, status)
        // UTF-8 BOM so Excel opens accented names correctly; the bytes are still plain CSV.
        val body = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray(Charsets.UTF_8)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + service.exportFileName(schoolId) + "\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
            .body(body)
    }

    @GetMapping("/{studentId}")
    fun detail(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable studentId: String,
    ): LearnerDetailPayload = service.detail(current, schoolId, studentId)

    @PostMapping("/{studentId}/transfer")
    fun transfer(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable studentId: String,
        @RequestBody request: LearnerTransferRequest,
    ): LearnerTransferResultPayload = service.transfer(current, schoolId, studentId, request)
}
