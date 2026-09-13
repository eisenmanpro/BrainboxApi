package com.afrithecus.brainbox.api.classchat

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupEntity
import com.afrithecus.brainbox.api.classchat.entity.ClassGroupMemberEntity
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupMemberRepository
import com.afrithecus.brainbox.api.classchat.repository.ClassGroupRepository
import com.afrithecus.brainbox.api.classchat.web.SendMessageRequest
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.security.JwtTokenService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Realtime class-group chat transport (api_class_group_chat_changes.md section 6). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:classchatws;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    ],
)
class ClassChatWebSocketTests(
    @Autowired private val jwtTokenService: JwtTokenService,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val groupRepository: ClassGroupRepository,
    @Autowired private val memberRepository: ClassGroupMemberRepository,
    @Autowired private val classChatService: ClassChatService,
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
        email = phone + "@chatws.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        isActive = true
        isVerified = true
    })

    private fun connect(path: String, token: String): Pair<WebSocket, LinkedBlockingQueue<String>> {
        val queue = LinkedBlockingQueue<String>()
        val ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Authorization", "Bearer " + token)
            .buildAsync(URI.create("ws://localhost:" + port + path), WsClient(queue))
            .join()
        return ws to queue
    }

    private fun awaitType(queue: LinkedBlockingQueue<String>, type: String, timeoutMs: Long = 10_000): tools.jackson.databind.JsonNode {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
            val text = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            val node = objectMapper.readTree(text)
            if (node.get("type")?.asString() == type) return node
        }
        throw AssertionError("did not receive chat frame: " + type)
    }

    @Test
    fun `http send is pushed to connected group sockets`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755110001")
        val student = user(Role.STUDENT, "Alice Learner", "0755110002")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Grade 4 South"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        val group = groupRepository.save(ClassGroupEntity().apply {
            classId = clazz.id
            teacherId = teacher.id
            teacherName = teacher.name
            name = "Grade 4 South Chat"
        })
        memberRepository.save(ClassGroupMemberEntity().apply {
            groupId = group.id
            memberId = student.id
            memberName = student.name
            memberRole = "STUDENT"
        })

        val teacherToken = jwtTokenService.issueAccessToken(teacher.id, Role.TEACHER)
        val studentToken = jwtTokenService.issueAccessToken(student.id, Role.STUDENT)
        val (teacherWs, _) = connect("/ws/teacher/class-chat/" + group.id, teacherToken)
        val (studentWs, studentQueue) = connect("/ws/student/class-chat/" + group.id, studentToken)
        try {
            // HTTP send path pushes the saved message to the other member.
            classChatService.send(
                CurrentUser(teacher.id, Role.TEACHER),
                group.id.toString(),
                SendMessageRequest(text = "Hello class"),
                null,
                false,
                "cm_1",
            )
            val frame = awaitType(studentQueue, "message")
            check(frame.get("message").get("text").asString() == "Hello class")

            // Heartbeat is answered.
            studentWs.sendText(objectMapper.writeValueAsString(mapOf("type" to "heartbeat", "timestamp" to 1L)), true)
            awaitType(studentQueue, "heartbeat_ack")
        } finally {
            runCatching { teacherWs.abort() }
            runCatching { studentWs.abort() }
        }
    }

    @Test
    fun `handshake rejects an unauthenticated socket`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755110011")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Grade 4 North"
            gradeLevel = "Grade 4"
            subject = "Mathematics"
            isActive = true
        })
        val group = groupRepository.save(ClassGroupEntity().apply {
            classId = clazz.id
            teacherId = teacher.id
            teacherName = teacher.name
            name = "Grade 4 North Chat"
        })
        val failed = runCatching {
            HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/teacher/class-chat/" + group.id), WsClient(LinkedBlockingQueue()))
                .join()
        }
        check(failed.isFailure)
    }
}
