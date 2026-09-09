package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.common.error.ApiError
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Writes the standard error envelope for security failures (doc 11 §8.1),
 * so 401/403 responses share the exact shape of API-level errors.
 */
@Component
class SecurityEnvelopeWriter(private val objectMapper: ObjectMapper) {

    fun writeUnauthorized(request: HttpServletRequest, response: HttpServletResponse) =
        write(request, response, HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHORIZED, "Authentication required")

    fun writeForbidden(request: HttpServletRequest, response: HttpServletResponse) =
        write(request, response, HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, "Access denied")

    private fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        code: ApiErrorCode,
        message: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(
            objectMapper.writeValueAsString(
                ApiError(
                    error = code.name,
                    message = message,
                    timestamp = System.currentTimeMillis(),
                    path = request.requestURI,
                )
            )
        )
    }
}

@Component
class RestAuthenticationEntryPoint(
    private val envelopeWriter: SecurityEnvelopeWriter,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        envelopeWriter.writeUnauthorized(request, response)
    }
}

@Component
class RestAccessDeniedHandler(
    private val envelopeWriter: SecurityEnvelopeWriter,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        envelopeWriter.writeForbidden(request, response)
    }
}
