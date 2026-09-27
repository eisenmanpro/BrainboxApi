package com.afrithecus.brainbox.api.console

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.PlatformSender
import com.afrithecus.brainbox.api.messaging.repository.MessageRepository
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import com.afrithecus.brainbox.api.push.PushMessage
import com.afrithecus.brainbox.api.push.PushSender
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A direct message must reach the recipient's device, not just their inbox.
 *
 * This class is deliberately **not** `@Transactional`: the fan-out dispatches after commit, so a
 * rolled-back test transaction would never show the push. It therefore runs against its own
 * in-memory database (the rest of the suite must not see its committed rows) and cleans up after
 * itself. The pushed payload is asserted exactly as a device would receive it.
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:consolepush;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsoleMessagePushTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val sender: RecordingPushSender,
    @Autowired private val messageRepository: MessageRepository,
    @Autowired private val deviceTokens: DeviceTokenRepository,
    @Autowired private val notifications: NotificationRepository,
    @Autowired private val consoleNotifications: ConsoleNotificationRepository,
) {

    /** Captures what a device would have been sent. */
    class RecordingPushSender : PushSender {
        val calls = CopyOnWriteArrayList<Pair<List<String>, PushMessage>>()
        override fun send(tokens: List<String>, message: PushMessage): List<String> {
            calls += tokens to message
            return emptyList()
        }
    }

    @TestConfiguration
    class RecordingPushConfig {
        @Bean
        @Primary
        fun recordingPushSender(): RecordingPushSender = RecordingPushSender()
    }

    private val createdUsers = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        // This test commits, unlike the rest of the suite, so it puts everything back:
        // the messages, the console row it queued, the device token and both accounts.
        messageRepository.deleteAllInBatch(
            messageRepository.findAll().filter {
                it.senderId == PlatformSender.USER_ID || it.recipientId == PlatformSender.USER_ID
            },
        )
        consoleNotifications.deleteAllInBatch(
            consoleNotifications.findAll().filter { it.createdBy in createdUsers },
        )
        notifications.deleteAllInBatch(
            notifications.findAll().filter { it.userId in createdUsers },
        )
        createdUsers.forEach { userId ->
            deviceTokens.findAllByUserId(userId).forEach { deviceTokens.delete(it) }
        }
        users.deleteAllById(createdUsers)
        createdUsers.clear()
    }

    @Test
    fun `a console message pushes the recipient and still writes no alert row`() {
        val owner = account("0755080030", Role.ADMIN, listOf(PlatformPermission.PLATFORM_ADMIN))
        val learner = account("0755080031", Role.STUDENT)
        val ownerToken = token(owner)
        val learnerToken = token(learner)

        mockMvc.perform(
            post("/push/device").header("Authorization", "Bearer $learnerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"fcm-token-console-message","platform":"ANDROID","appVersion":"1.0"}""")
        ).andExpect(status().isOk)
        sender.calls.clear()

        val before = notifications.findAll().count { it.userId == learner.id }

        mockMvc.perform(
            post("/admin/console/messages").header("Authorization", "Bearer $ownerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"title":"Welcome to Brainbox",""" +
                        """"body":"Your account is ready. Reply here if you need help.",""" +
                        """"audienceType":"USER","audienceValue":"${learner.id}"}""",
                )
        ).andExpect(status().isOk)

        val call = sender.calls.singleOrNull()
        check(call != null) { "exactly one push expected, saw " + sender.calls.size }
        check(call.first == listOf("fcm-token-console-message")) { call.first.toString() }
        val push = call.second
        check(push.title == "Welcome to Brainbox") { push.title }
        check(push.message == "Your account is ready. Reply here if you need help.") { push.message }
        check(push.type == "MESSAGE") { push.type.toString() }
        check(push.actionRoute == "message_centre") { push.actionRoute.toString() }
        check(push.actionLabel == "Open")
        check(push.metadata["sender"] == "Brainbox")
        check(push.metadata["notificationId"] != null)

        // The push is the alert: the message itself is correspondence, so the server wrote no
        // notification row for it.
        val after = notifications.findAll().count { it.userId == learner.id }
        check(after == before) { "a message must not create an alert row (before=$before after=$after)" }
    }

    private fun account(
        phone: String,
        role: Role,
        capabilities: List<PlatformPermission> = emptyList(),
    ): UserEntity = users.save(
        UserEntity().apply {
            phoneNumber = phone
            email = phone + "@consolepush.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Push " + role.name + " " + phone.takeLast(4)
            this.role = role
            isActive = true
            isVerified = true
            platformPermissions =
                capabilities.takeIf { it.isNotEmpty() }?.let { PlatformPermission.format(it.toSet()) }
        },
    ).also { createdUsers += it.id }

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${user.phoneNumber}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }
}
