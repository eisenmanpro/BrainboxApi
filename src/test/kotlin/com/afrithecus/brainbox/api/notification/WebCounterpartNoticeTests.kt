package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.PlatformSender
import com.afrithecus.brainbox.api.messaging.model.Folder
import com.afrithecus.brainbox.api.messaging.repository.MessageRepository
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * product_ops_roadmap item 6: teachers learn about the web counterpart through a
 * Brainbox-originating message plus a SYSTEM notification, exactly once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WebCounterpartNoticeTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val schools: SchoolRepository,
    @Autowired private val notifications: NotificationRepository,
    @Autowired private val messages: MessageRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun login(email: String) {
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk)
    }

    private fun teacher(email: String, schoolId: UUID): UserEntity =
        users.save(UserEntity().apply {
            phoneNumber = "0778" + (100000..999999).random()
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Web Notice Teacher"
            role = Role.TEACHER
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        })

    @Test
    fun `a teacher is told once about the web workspace, from Brainbox`() {
        val school = schools.save(SchoolEntity().apply { name = "Web Notice School" })
        val email = "web.notice.teacher@test"
        val t = teacher(email, school.id)

        login(email)
        login(email) // a second login must not repeat the announcement

        val announcement = notifications.findAllByUserIdOrderByCreatedAtDesc(t.id)
            .filter { it.dedupeKey == "web-counterpart" }
        check(announcement.size == 1) { "expected exactly one announcement, got " + announcement.size }
        val row = announcement.first()
        check(row.type == NotificationType.SYSTEM)
        check(row.metadata?.contains("webUrl") == true) { "the announcement must carry the URL" }

        val inbox = messages.findAllByRecipientIdAndFolderOrderByCreatedAtDesc(t.id, Folder.inbox)
        check(inbox.size == 1) { "expected one Brainbox inbox message, got " + inbox.size }
        check(inbox.first().senderId == PlatformSender.USER_ID) { "the message must come from Brainbox" }
    }
}
