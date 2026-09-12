package com.afrithecus.brainbox.api.live.ws

import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.repository.LiveClassRepository
import com.afrithecus.brainbox.api.live.repository.LiveRegistrationRepository
import com.afrithecus.brainbox.api.security.JwtTokenService
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.UUID

/**
 * Authenticates and authorizes the live-class signaling handshake. The client
 * sends the access token as an Authorization header (and its id in userId);
 * only the host, a registered learner, a same-school account or an admin may
 * open the channel for a class.
 */
@Component
class LiveSignalingHandshakeInterceptor(
    private val jwtTokenService: JwtTokenService,
    private val classRepository: LiveClassRepository,
    private val registrationRepository: LiveRegistrationRepository,
    private val userRepository: UserRepository,
) : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val token = bearerToken(request) ?: return reject(response)
        val claims = runCatching { jwtTokenService.parseAccessToken(token) }.getOrNull() ?: return reject(response)
        val classId = classIdFrom(request) ?: return reject(response)
        val clazz = classRepository.findById(classId).orElse(null) ?: return reject(response)
        val queryUserId = queryParam(request, "userId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (queryUserId != null && queryUserId != claims.userId) return reject(response)
        val user = userRepository.findById(claims.userId).orElse(null) ?: return reject(response)
        val host = clazz.teacherId == claims.userId
        val registered = registrationRepository.findByClassIdAndStudentId(classId, claims.userId) != null
        val sameSchool = clazz.schoolId != null && clazz.schoolId == user.schoolId
        if (!host && !registered && !sameSchool && claims.role != Role.ADMIN) return reject(response)
        attributes[LiveSignalingHandler.CLASS_ID] = classId
        attributes[LiveSignalingHandler.USER_ID] = claims.userId
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    private fun bearerToken(request: ServerHttpRequest): String? {
        val header = request.headers.getFirst("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }

    private fun classIdFrom(request: ServerHttpRequest): UUID? {
        val segments = request.uri.path.trim('/').split('/')
        val raw = segments.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    private fun queryParam(request: ServerHttpRequest, name: String): String? =
        request.uri.query?.split('&')?.mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
        }?.firstOrNull { it.first == name }?.second

    private fun reject(response: ServerHttpResponse): Boolean {
        response.setStatusCode(HttpStatus.UNAUTHORIZED)
        return false
    }
}
