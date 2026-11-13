package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.identity.model.AccountStatus
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Server-side enforcement of the account state machine (gaps.md item 1D). An account may hold a
 * session while its email is unconfirmed or an ICT admin has not approved it, but it must not reach
 * any data: the client's waiting screen is a courtesy, not the gate. Without this filter a pending
 * teacher could call the API directly and read the school's roster.
 *
 * Every account-surface route stays reachable so the client can render the waiting/verification
 * state, and the public reads stay reachable because they are not data.
 */
@Component
class AccountStateFilter(
    private val userRepository: UserRepository,
    private val envelopeWriter: SecurityEnvelopeWriter,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? CurrentUser
        if (principal == null || isAlwaysAllowed(request.requestURI)) {
            filterChain.doFilter(request, response)
            return
        }
        val user = userRepository.findById(principal.userId).orElse(null)
        if (user == null) {
            envelopeWriter.writeUnauthorized(request, response)
            return
        }
        if (!user.isActive) {
            envelopeWriter.writeRestricted(
                request, response, ApiErrorCode.ACCOUNT_INACTIVE,
                "This account is not active. Contact your school's admin.",
            )
            return
        }
        if (user.emailVerificationRequired && user.emailVerifiedAt == null) {
            envelopeWriter.writeRestricted(
                request, response, ApiErrorCode.EMAIL_NOT_VERIFIED,
                "Verify your email address to continue.",
            )
            return
        }
        // The staff approval gate (gaps.md item 1D). Learner approvals remain the class
        // teacher's workflow; this gate is about teacher accounts that self-signup.
        if (user.role == Role.TEACHER && user.verificationStatus != AccountStatus.VERIFIED) {
            envelopeWriter.writeRestricted(
                request, response, ApiErrorCode.ACCOUNT_PENDING,
                "Your account is waiting for approval. You will get access once it is approved.",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun isAlwaysAllowed(uri: String): Boolean = ALWAYS_ALLOWED.any { uri == it || uri.startsWith(it) }

    private companion object {
        val ALWAYS_ALLOWED = listOf(
            "/auth/",
            "/actuator",
            "/error",
            "/ws/",
            "/schools/",
            "/news/",
            "/landing/",
            "/media/",
            "/about/",
            "/models/",
            "/cbc/public/",
            "/webhooks/",
            "/teacher/reports/download/",
            "/payments/mpesa/callback",
        )
    }
}
