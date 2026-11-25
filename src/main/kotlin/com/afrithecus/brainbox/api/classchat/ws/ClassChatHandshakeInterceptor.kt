package com.afrithecus.brainbox.api.classchat.ws

import com.afrithecus.brainbox.api.classchat.ClassChatService
import com.afrithecus.brainbox.api.security.JwtTokenService
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.UUID

/**
 * Authenticates a class-chat socket and proves the caller may read the group.
 *
 * The bearer arrives either as an `Authorization` header (native clients) or as a
 * `token` / `access_token` query parameter, because browsers cannot set headers on a
 * WebSocket handshake. Authorization itself is delegated to [ClassChatService.mayRead],
 * which reuses the REST read gate: school-scope system groups open by school+role,
 * explicit members, the creator, parents of a member, and school/platform moderators.
 */
@Component
class ClassChatHandshakeInterceptor(
    private val jwtTokenService: JwtTokenService,
    private val chatService: ClassChatService,
) : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val token = accessToken(request) ?: return reject(response)
        val claims = runCatching { jwtTokenService.parseAccessToken(token) }.getOrNull() ?: return reject(response)
        val groupId = groupIdFrom(request) ?: return reject(response)
        val queryUserId = queryParam(request, "userId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        // A caller may name themselves for client bookkeeping, but never another user.
        if (queryUserId != null && queryUserId != claims.userId) return reject(response)
        if (!chatService.mayRead(claims.userId, groupId)) return reject(response)
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

    private fun accessToken(request: ServerHttpRequest): String? {
        bearerToken(request)?.let { return it }
        return queryParam(request, "token")?.takeIf { it.isNotBlank() }
            ?: queryParam(request, "access_token")?.takeIf { it.isNotBlank() }
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
