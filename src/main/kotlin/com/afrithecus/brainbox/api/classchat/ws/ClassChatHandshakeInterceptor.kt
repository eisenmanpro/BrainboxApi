package com.afrithecus.brainbox.api.classchat.ws

import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMemberRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupRepository
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.security.JwtTokenService
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.UUID

/**
 * Authenticates a class-chat socket: the bearer token must match the userId and
 * the caller must belong to the group (owner teacher, group member, a parent of a
 * member, or an admin).
 */
@Component
class ClassChatHandshakeInterceptor(
    private val jwtTokenService: JwtTokenService,
    private val groupRepository: ClassGroupRepository,
    private val memberRepository: ClassGroupMemberRepository,
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
        val groupId = groupIdFrom(request) ?: return reject(response)
        val group = groupRepository.findById(groupId).orElse(null) ?: return reject(response)
        val queryUserId = queryParam(request, "userId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (queryUserId != null && queryUserId != claims.userId) return reject(response)
        if (!mayAccess(claims.userId, claims.role, groupId, group.teacherId)) return reject(response)
        attributes[ClassChatSocketHandler.GROUP_ID] = groupId
        attributes[ClassChatSocketHandler.USER_ID] = claims.userId
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit

    private fun mayAccess(userId: UUID, role: Role, groupId: UUID, teacherId: UUID): Boolean {
        if (role == Role.ADMIN) return true
        if (teacherId == userId) return true
        if (memberRepository.findByGroupIdAndMemberId(groupId, userId) != null) return true
        if (role == Role.PARENT) {
            return userRepository.findByParentUserId(userId).any { child ->
                memberRepository.findByGroupIdAndMemberId(groupId, child.id) != null
            }
        }
        return false
    }

    private fun bearerToken(request: ServerHttpRequest): String? {
        val header = request.headers.getFirst("Authorization") ?: return null
        if (!header.startsWith("Bearer ")) return null
        return header.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }

    private fun groupIdFrom(request: ServerHttpRequest): UUID? {
        val raw = request.uri.path.trim('/').split('/').lastOrNull()?.takeIf { it.isNotEmpty() } ?: return null
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
