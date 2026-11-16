package com.afrithecus.brainbox.api.classes.web

import com.afrithecus.brainbox.api.classes.AdminClassService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin class management for the School Config redesign (findings "ADMIN SCHOOL CONFIG 3"):
 * list the school's classes, add one under an existing grade, rename/re-grade it, reassign
 * its class teacher and delete an empty one. School-scoped; ICT admin own school, platform
 * admin any school.
 */
@RestController
@RequestMapping("/admin/schools/{schoolId}/classes")
@PreAuthorize("hasAnyRole('ADMIN','ICT_ADMIN')")
class AdminClassController(private val service: AdminClassService) {

    @GetMapping
    fun list(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
    ): List<AdminClassPayload> = service.list(current, schoolId)

    @PostMapping
    fun create(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @RequestBody request: CreateAdminClassRequest,
    ): AdminClassPayload = service.create(current, schoolId, request)

    @PutMapping("/{classId}")
    fun update(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable classId: String,
        @RequestBody request: UpdateAdminClassRequest,
    ): AdminClassPayload = service.update(current, schoolId, classId, request)

    /** Reassign the class teacher; the previous teacher loses the class, not their account. */
    @PutMapping("/{classId}/teacher")
    fun assignTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable classId: String,
        @RequestBody request: AssignClassTeacherRequest,
    ): AdminClassPayload = service.assignTeacher(current, schoolId, classId, request)

    /** 204 on success, 409 when the class still has learners. */
    @DeleteMapping("/{classId}")
    fun delete(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable schoolId: String,
        @PathVariable classId: String,
    ): ResponseEntity<Void> {
        service.delete(current, schoolId, classId)
        return ResponseEntity.noContent().build()
    }
}
