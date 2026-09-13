package com.afrithecus.brainbox.api.auth.web

import com.afrithecus.brainbox.api.auth.AuthService
import com.afrithecus.brainbox.api.identity.SchoolRegistrationService
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * /auth endpoints per doc 01 §1. Public: signup, login, refresh. Protected:
 * logout, me (bearer token required).
 */
@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val schoolRegistrationService: SchoolRegistrationService,
) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest, http: HttpServletRequest): AuthResponse =
        authService.signup(request, http.getHeader(DEVICE_ID_HEADER))

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, http: HttpServletRequest): AuthResponse =
        authService.login(request, http.getHeader(DEVICE_ID_HEADER))

    /** Phone-first login used by the teacher/parent flows (AuthApi.loginWithPhone). */
    @PostMapping("/login/phone")
    fun loginWithPhone(@Valid @RequestBody request: PhoneLoginRequest, http: HttpServletRequest): AuthResponse =
        authService.login(
            LoginRequest(identifier = request.phoneNumber, password = request.password),
            http.getHeader(DEVICE_ID_HEADER),
        )

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse =
        authService.refresh(request)

    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal currentUser: CurrentUser): ResponseEntity<Void> {
        authService.logout(currentUser)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal currentUser: CurrentUser): AuthResponse =
        authService.me(currentUser)

    @PostMapping("/change-password")
    fun changePassword(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): AuthResponse = authService.changePassword(currentUser, request)

    @PostMapping("/register-school")
    fun registerSchool(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: SchoolRegistrationRequest,
    ): AuthResponse = schoolRegistrationService.register(currentUser, request)

    @PostMapping("/switch-session")
    fun switchSession(
        @AuthenticationPrincipal currentUser: CurrentUser,
        @Valid @RequestBody request: SwitchSessionRequest,
        http: HttpServletRequest,
    ): AuthResponse = authService.switchSession(currentUser, request, http.getHeader(DEVICE_ID_HEADER))

    private companion object {
        const val DEVICE_ID_HEADER = "X-Device-Id"
    }
}
