package com.afrithecus.brainbox.api.classchat.ws

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Realtime class-group chat transport (docs/ongoing/api_class_group_chat_changes.md
 * section 6). One channel per group; HTTP remains the authoritative write path and
 * the server pushes each saved message (and deletions) to every open socket.
 */
@Component
class ClassChatSocketHandler(private val mapper: ObjectMapper) : TextWebSocketHandler() {

    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val groupId = session.attributes[GROUP_ID] as? UUID ?: run {
            runCatching { session.close(CloseStatus.POLICY_VIOLATION) }
            return
        }
        sessions.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val groupId = session.attributes[GROUP_ID] as? UUID ?: return
        val node = runCatching { mapper.readTree(message.payload) }.getOrNull() ?: return
        val type = node.get("type")?.asString()
        when (type) {
            "heartbeat", "ping" -> sendRaw(session, mapOf("type" to "heartbeat_ack"))
            "message", "chat_message", "new_message" -> {
                val body = node.get("message") ?: node
                broadcast(groupId, mapOf("type" to "message", "message" to body), except = session)
            }
            null, "" -> if (node.get("id") != null && node.get("groupId") != null) {
                broadcast(groupId, node, except = session)
            }
            else -> Unit
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val groupId = session.attributes[GROUP_ID] as? UUID ?: return
        sessions[groupId]?.remove(session)
        if (sessions[groupId].isNullOrEmpty()) sessions.remove(groupId)
    }

    fun broadcastMessage(groupId: UUID, payload: Any) {
        broadcast(groupId, mapOf("type" to "message", "message" to payload))
    }

    fun broadcastDeleted(groupId: UUID, messageId: String) {
        broadcast(groupId, mapOf("type" to "message_deleted", "messageId" to messageId))
    }

    fun connectionCount(groupId: UUID): Int = sessions[groupId]?.size ?: 0

    private fun broadcast(groupId: UUID, payload: Any, except: WebSocketSession? = null) {
        val text = mapper.writeValueAsString(payload)
        sessions[groupId]?.toList()?.forEach { target ->
            if (target !== except && target.isOpen) sendText(target, text)
        }
    }

    private fun sendRaw(session: WebSocketSession, payload: Any) =
        sendText(session, mapper.writeValueAsString(payload))

    private fun sendText(session: WebSocketSession, text: String) {
        synchronized(session) {
            runCatching { session.sendMessage(TextMessage(text)) }
        }
    }

    companion object {
        const val GROUP_ID = "groupId"
        const val USER_ID = "userId"
    }
}
