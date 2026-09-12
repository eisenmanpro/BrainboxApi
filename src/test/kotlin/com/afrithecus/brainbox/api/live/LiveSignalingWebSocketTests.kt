package com.afrithecus.brainbox.api.live

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.entity.LiveClassEntity
import com.afrithecus.brainbox.api.live.entity.LiveRegistrationEntity
import com.afrithecus.brainbox.api.live.model.LiveClassStatus
import com.afrithecus.brainbox.api.live.repository.LiveClassRepository
import com.afrithecus.brainbox.api.live.repository.LiveRegistrationRepository
import com.afrithecus.brainbox.api.security.JwtTokenService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.security.crypto.password.PasswordEncoder
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Instant
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live-class WebRTC signaling channel (doc 09 section 2.7): JWT handshake,
 * join/leave, verbatim SDP/ICE relay, heartbeat ack and class-ended broadcast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:livews;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    ],
)
class LiveSignalingWebSocketTests(
    @Autowired private val jwtTokenService: JwtTokenService,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val classRepository: LiveClassRepository,
    @Autowired private val registrationRepository: LiveRegistrationRepository,
    @Autowired private val liveClassService: TeacherLiveClassService,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @LocalServerPort private val port: Int,
) {

    private class WsClient(val queue: LinkedBlockingQueue<String>) : WebSocket.Listener {
        private val buffer = StringBuilder()
        override fun onOpen(ws: WebSocket) {
            ws.request(1)
        }

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                queue.add(buffer.toString())
                buffer.clear()
            }
            ws.request(1)
            return null
        }
    }

    private fun user(role: Role, name: String, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@livews.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        isActive = true
        isVerified = true
    })

    /** Polls past any unrelated frames (join/leave races) until [type] arrives. */
    private fun awaitType(
        queue: LinkedBlockingQueue<String>,
        type: String,
        timeoutMs: Long = 10_000,
    ): tools.jackson.databind.JsonNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val text = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            val node = objectMapper.readTree(text)
            if (node.get("type")?.asString() == type) return node
        }
        throw AssertionError("did not receive signaling frame: " + type)
    }

    private fun connect(classId: java.util.UUID, token: String): Pair<WebSocket, LinkedBlockingQueue<String>> {
        val queue = LinkedBlockingQueue<String>()
        val ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Authorization", "Bearer " + token)
            .buildAsync(URI.create("ws://localhost:" + port + "/ws/live/" + classId), WsClient(queue))
            .join()
        return ws to queue
    }

    @Test
    fun `signaling relay joins, relays sdp and broadcasts class ended`() {
        val teacher = user(Role.TEACHER, "Host Teacher", "0755050001")
        val student = user(Role.STUDENT, "Joined Student", "0755050002")
        val start = Instant.now().plusSeconds(3600)
        val clazz = classRepository.save(LiveClassEntity().apply {
            teacherId = teacher.id
            teacherName = teacher.name
            title = "Signaling Test"
            subject = "Mathematics"
            scheduledStart = start
            scheduledEnd = start.plusSeconds(3600)
            status = LiveClassStatus.SCHEDULED
        })
        registrationRepository.save(LiveRegistrationEntity().apply {
            classId = clazz.id
            studentId = student.id
        })

        val (hostWs, hostQueue) = connect(clazz.id, jwtTokenService.issueAccessToken(teacher.id, Role.TEACHER))
        val (studentWs, studentQueue) = connect(clazz.id, jwtTokenService.issueAccessToken(student.id, Role.STUDENT))

        try {
            // The host learns the learner joined.
            val joinedNode = awaitType(hostQueue, "user_joined")
            check(joinedNode.get("userId").asString() == student.id.toString())

            // A verbatim SDP offer is relayed to the peer.
            hostWs.sendText(
                objectMapper.writeValueAsString(
                    mapOf("type" to "sdp_offer", "sdp" to "v=0-offer", "from" to teacher.id.toString(), "timestamp" to 1),
                ),
                true,
            )
            val offerNode = awaitType(studentQueue, "sdp_offer")
            check(offerNode.get("sdp").asString() == "v=0-offer")
            check(offerNode.get("from").asString() == teacher.id.toString())

            // Heartbeat is answered directly.
            studentWs.sendText(
                objectMapper.writeValueAsString(mapOf("type" to "heartbeat", "timestamp" to 2)),
                true,
            )
            awaitType(studentQueue, "heartbeat_ack")

            // Ending the class over REST broadcasts class_ended to every peer.
            liveClassService.end(teacher, clazz.id.toString())
            awaitType(studentQueue, "class_ended")
            awaitType(hostQueue, "class_ended")
        } finally {
            runCatching { hostWs.abort() }
            runCatching { studentWs.abort() }
        }
    }

    @Test
    fun `handshake rejects an unauthenticated socket`() {
        val teacher = user(Role.TEACHER, "Host Teacher", "0755050011")
        val start = Instant.now().plusSeconds(3600)
        val clazz = classRepository.save(LiveClassEntity().apply {
            teacherId = teacher.id
            teacherName = teacher.name
            title = "No Auth"
            subject = "Mathematics"
            scheduledStart = start
            scheduledEnd = start.plusSeconds(3600)
            status = LiveClassStatus.SCHEDULED
        })
        val failed = runCatching {
            HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/live/" + clazz.id), WsClient(LinkedBlockingQueue()))
                .join()
        }
        check(failed.isFailure) { "an unauthenticated handshake should be rejected" }
    }
}
