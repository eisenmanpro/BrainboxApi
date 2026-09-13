package com.afrithecus.brainbox.api.auth.web

import com.afrithecus.brainbox.api.auth.TeacherAuthService
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Teacher auth lifecycle under /auth (docs/ongoing/api_teacher_roster_changes.md).
 * signup/teacher and validate-ctc are public; every decision derives the actor
 * from the bearer token and re-checks the role server-side.
 */
@RestController
@RequestMapping("/auth")
class TeacherAuthController(private val service: TeacherAuthService) {

    @PostMapping("/signup/teacher")
    fun teacherSignup(
        @Valid @RequestBody request: TeacherSignupRequest,
        http: HttpServletRequest,
    ): TeacherSignupResponse = service.teacherSignup(request, http.getHeader(DEVICE_ID_HEADER))

    @PostMapping("/validate-ctc")
    fun validateCtc(@Valid @RequestBody request: ValidateCtcRequest): CtcValidationPayload =
        service.validateCtc(request)

    @PostMapping("/rotate-ctc")
    fun rotateCtc(@AuthenticationPrincipal current: CurrentUser): RotateCtcResponsePayload =
        service.rotateCtc(current)

    @PostMapping("/ctc/freeze")
    fun freezeCtc(@AuthenticationPrincipal current: CurrentUser): AuthResponse =
        service.setCtcFrozen(current, frozen = true)

    @PostMapping("/ctc/unfreeze")
    fun unfreezeCtc(@AuthenticationPrincipal current: CurrentUser): AuthResponse =
        service.setCtcFrozen(current, frozen = false)

    @PostMapping("/students/{studentId}/approve")
    fun approveStudent(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable studentId: String,
    ): AuthResponse = service.decideUser(current, studentId, AccountStatus.VERIFIED, "Student approved")

    @PostMapping("/students/{studentId}/reject")
    fun rejectStudent(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable studentId: String,
    ): AuthResponse = service.decideUser(current, studentId, AccountStatus.REJECTED, "Student rejected")

    @PostMapping("/teachers/{teacherId}/approve")
    fun approveTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable teacherId: String,
    ): AuthResponse = service.decideUser(current, teacherId, AccountStatus.VERIFIED, "Teacher approved")

    @PostMapping("/teachers/{teacherId}/reject")
    fun rejectTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable teacherId: String,
    ): AuthResponse = service.decideUser(current, teacherId, AccountStatus.REJECTED, "Teacher rejected")

    @PostMapping("/teachers/{teacherId}/freeze")
    fun freezeTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable teacherId: String,
    ): AuthResponse = service.freezeTeacher(current, teacherId, frozen = true)

    @PostMapping("/teachers/{teacherId}/unfreeze")
    fun unfreezeTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable teacherId: String,
    ): AuthResponse = service.freezeTeacher(current, teacherId, frozen = false)

    @PostMapping("/teachers/{teacherId}/transfer")
    fun transferTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable teacherId: String,
        @RequestBody request: TeacherTransferRequest,
    ): AuthResponse = service.transferTeacher(current, teacherId, request)

    @PutMapping("/teachers/{teacherId}")
    fun updateTeacher(
        @AuthenticationPrincipal current: CurrentUser,
        @PathVariable teacherId: String,
        @RequestBody request: TeacherInfoUpdateRequest,
    ): AuthResponse = service.updateTeacher(current, teacherId, request)

    private companion object {
        const val DEVICE_ID_HEADER = "X-Device-Id"
    }
}
