package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Extracts "Authorization: Bearer {accessToken}", validates it and populates the
 * security context. Invalid/expired tokens are simply ignored; protected
 * endpoints then yield the standard 401 envelope via the entry point.
 */
@Component
class AuthTokenFilter(
    private val jwtTokenService: JwtTokenService,
    private val userSessionRepository: UserSessionRepository,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            val token = header.substring(BEARER_PREFIX.length).trim()
            runCatching { jwtTokenService.parseAccessToken(token) }
                .onSuccess { claims ->
                    // A revoked session (logout, password change, admin deactivation)
                    // invalidates its access tokens immediately; without this check the
                    // JWT would stay valid until its TTL (24h) even after logout.
                    if (claims.sessionId != null &&
                        userSessionRepository.findByIdAndIsActiveTrue(claims.sessionId)?.userId != claims.userId
                    ) {
                        return@onSuccess
                    }
                    val principal = CurrentUser(
                        userId = claims.userId,
                        role = claims.role,
                        subRole = claims.subRole,
                        sessionId = claims.sessionId,
                        deviceId = claims.deviceId,
                    )
                    val authentication = UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities.map(::SimpleGrantedAuthority),
                    )
                    SecurityContextHolder.getContext().authentication = authentication
                }
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
