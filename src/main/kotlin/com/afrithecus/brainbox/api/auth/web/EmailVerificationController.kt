package com.afrithecus.brainbox.api.auth.web

import com.afrithecus.brainbox.api.auth.EmailVerificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public email-verification endpoints (gaps.md item 3): the client can ask for a new code and
 * confirm the address before the first sign-in. Neither endpoint exposes whether an account exists.
 */
@RestController
@RequestMapping("/auth/email")
class EmailVerificationController(private val service: EmailVerificationService) {

    @PostMapping("/send-code")
    fun sendCode(@Valid @RequestBody request: EmailCodeRequest): EmailCodeMessagePayload =
        service.requestCode(request.identifier)

    @PostMapping("/verify")
    fun verify(
        @Valid @RequestBody request: EmailCodeVerifyRequest,
        http: HttpServletRequest,
    ): AuthResponse = service.verify(request.identifier, request.code, http.getHeader(DEVICE_ID_HEADER))

    private companion object {
        const val DEVICE_ID_HEADER = "X-Device-Id"
    }
}
