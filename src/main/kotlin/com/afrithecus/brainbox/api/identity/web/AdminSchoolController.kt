package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.identity.AdminSchoolService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * ICT-admin school management reads/writes (docs/ongoing/api_admin_changes.md).
 * The school is a resource id; access is derived from the token inside the
 * service (platform ADMIN any school, ICT_ADMIN only their own).
 */
@RestController
@RequestMapping("/admin/schools")
@PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
class AdminSchoolController(private val service: AdminSchoolService) {

    @GetMapping("/{schoolId}/analytics")
    fun analytics(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): SchoolAnalyticsPayload = service.analytics(current, schoolId)

    @GetMapping("/{schoolId}/grades")
    fun grades(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): List<GradeConfigSummaryPayload> = service.grades(current, schoolId)

    @GetMapping("/{schoolId}/approvals")
    fun approvals(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): List<UserApprovalRequestPayload> = service.approvals(current, schoolId)

    @GetMapping("/{schoolId}/attendance/overview")
    fun attendanceOverview(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): SchoolAttendanceOverviewPayload = service.attendanceOverview(current, schoolId)

    @GetMapping("/{schoolId}/system-settings")
    fun systemSettings(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): SystemSettingsPayload = service.systemSettings(current, schoolId)

    @PutMapping("/{schoolId}/system-settings")
    fun updateSystemSettings(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestBody request: SystemSettingsPayload,
    ): SystemSettingsPayload = service.updateSystemSettings(current, schoolId, request)

    @GetMapping("/{schoolId}/audit-logs")
    fun auditLogs(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) before: Long?,
    ): List<AuditLogEntryPayload> = service.auditLogs(current, schoolId, limit, before)

    @PostMapping("/{schoolId}/backup")
    fun backup(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): BackupResultPayload = service.backup(current, schoolId)

    @GetMapping("/{schoolId}/backups")
    fun backups(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): List<BackupSummaryPayload> = service.backups(current, schoolId)

    /** Streams a stored backup as a JSON attachment (admin-authenticated). */
    @GetMapping("/{schoolId}/backups/{backupId}/download")
    fun downloadBackup(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable backupId: String,
    ): ResponseEntity<ByteArray> {
        val file = service.downloadBackup(current, schoolId, backupId)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + file.fileName)
            .body(file.bytes)
    }
}
