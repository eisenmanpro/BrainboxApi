package com.afrithecus.brainbox.api.live.ws

import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebRTC signaling relay for live classes (doc 09 section 2.7). One channel per
 * class: SDP offers/answers and ICE candidates are relayed verbatim to the other
 * peers (the client stamps each frame with its own "from" id), while the server
 * emits join/leave, heartbeat acks and class-ended control frames.
 */
@Component
class LiveSignalingHandler(
    private val mapper: ObjectMapper,
    private val userRepository: UserRepository,
) : TextWebSocketHandler() {

    private val sessions = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val classId = session.attributes[CLASS_ID] as? UUID
        val userId = session.attributes[USER_ID] as? UUID
        if (classId == null || userId == null) {
            runCatching { session.close(CloseStatus.POLICY_VIOLATION) }
            return
        }
        sessions.computeIfAbsent(classId) { ConcurrentHashMap.newKeySet() }.add(session)
        val name = userRepository.findById(userId).orElse(null)?.name ?: "Participant"
        broadcast(
            classId,
            mapOf(
                "type" to "user_joined",
                "userId" to userId.toString(),
                "userName" to name,
                "timestamp" to System.currentTimeMillis(),
            ),
            except = session,
        )
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val classId = session.attributes[CLASS_ID] as? UUID ?: return
        val node = runCatching { mapper.readTree(message.payload) }.getOrNull() ?: return
        when (node.get("type")?.asString()) {
            "heartbeat" -> sendRaw(
                session,
                mapOf("type" to "heartbeat_ack", "timestamp" to System.currentTimeMillis()),
            )
            null -> Unit
            else -> broadcast(classId, node, except = session)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        val classId = session.attributes[CLASS_ID] as? UUID ?: return
        val userId = session.attributes[USER_ID] as? UUID
        sessions[classId]?.remove(session)
        if (sessions[classId].isNullOrEmpty()) sessions.remove(classId)
        if (userId != null) {
            broadcast(
                classId,
                mapOf(
                    "type" to "user_left",
                    "userId" to userId.toString(),
                    "timestamp" to System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Called from the REST lifecycle when the host ends or cancels a class. */
    fun broadcastClassEnded(classId: UUID, reason: String) {
        broadcast(classId, mapOf("type" to "class_ended", "reason" to reason, "timestamp" to System.currentTimeMillis()))
        sessions.remove(classId)?.forEach { session -> runCatching { session.close(CloseStatus.NORMAL) } }
    }

    fun connectionCount(classId: UUID): Int = sessions[classId]?.size ?: 0

    private fun broadcast(classId: UUID, payload: Any, except: WebSocketSession? = null) {
        val text = mapper.writeValueAsString(payload)
        sessions[classId]?.toList()?.forEach { target ->
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
        const val CLASS_ID = "classId"
        const val USER_ID = "userId"
    }
}
