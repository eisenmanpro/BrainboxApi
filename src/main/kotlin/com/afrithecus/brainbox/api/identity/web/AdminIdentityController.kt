package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.auth.web.UserPayload
import com.afrithecus.brainbox.api.identity.admin.IdentityAdminService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.web.ResetPasswordResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Identity administration endpoints (doc 01 §2.2/§6.1/§7.2/§8.2/§9.2).
 * Every mapping is guarded by hasRole('ADMIN').
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminIdentityController(private val service: IdentityAdminService) {

    @GetMapping("/users")
    fun listUsers(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) schoolId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) search: String?,
    ): AdminUserListResponse = service.listUsers(role, schoolId, status, search, page ?: 0, limit ?: 20)

    @GetMapping("/users/{id}")
    fun getUser(@PathVariable id: String): UserPayload = service.getUser(id)

    @PatchMapping("/users/{id}")
    fun updateUser(@PathVariable id: String, @Valid @RequestBody request: UpdateUserRequest): UserPayload =
        service.updateUser(id, request)

    @DeleteMapping("/users/{id}")
    fun deactivateUser(@PathVariable id: String): ResponseEntity<Void> {
        service.deactivateUser(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/users/{id}/subscription")
    fun updateSubscription(
        @PathVariable id: String,
        @Valid @RequestBody request: SubscriptionUpdateRequest,
    ): ResponseEntity<Void> {
        service.updateSubscription(id, request)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/users/{id}/reset-password")
    fun resetPassword(@PathVariable id: String): ResetPasswordResponse = service.resetPassword(id)

    @PostMapping("/parent-link")
    fun linkParent(@Valid @RequestBody request: ParentLinkRequest): ResponseEntity<Void> {
        service.linkParent(request.parentId, request.childId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/users/{id}/approve")
    fun approveUser(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        service.setVerified(id, true, currentUser.userId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/users/{id}/reject")
    fun rejectUser(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        service.setVerified(id, false, currentUser.userId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PostMapping("/schools/{id}/teachers")
    fun createTeacher(@PathVariable id: String, @Valid @RequestBody request: CreateTeacherRequest): TeacherPayload =
        service.createTeacher(id, request)

    @GetMapping("/schools/{id}/teachers")
    fun listTeachers(@PathVariable id: String): List<TeacherPayload> = service.listTeachers(id)

    @DeleteMapping("/schools/{id}/teachers/{teacherId}")
    fun removeTeacher(@PathVariable id: String, @PathVariable teacherId: String): ResponseEntity<Void> {
        service.removeTeacher(id, teacherId)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @PatchMapping("/schools/{id}")
    fun updateSchool(@PathVariable id: String, @Valid @RequestBody request: UpdateSchoolRequest): SchoolPayload =
        service.updateSchool(id, request)

    @DeleteMapping("/schools/{id}")
    fun deactivateSchool(@PathVariable id: String): ResponseEntity<Void> {
        service.deactivateSchool(id)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }
}
