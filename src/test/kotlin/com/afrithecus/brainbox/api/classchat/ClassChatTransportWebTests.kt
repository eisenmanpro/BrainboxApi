package com.afrithecus.brainbox.api.classchat

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classchat.web.ClassGroupMessagePayload
import com.afrithecus.brainbox.api.classchat.web.ClassGroupPayload
import com.afrithecus.brainbox.api.classchat.web.MessageAttachmentPayload
import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Class-chat transport (docs/ongoing/api_class_group_chat_changes.md): parent and
 * student send/read/upload/vote, clientMessageId idempotency, announcement-only
 * and mute enforcement, membership gating, unread clearing and notification fan-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClassChatTransportWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val membershipRepository: ClassMembershipRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Transport " + phone
            this.role = role
            isActive = true
            isVerified = true
        })

    private fun login(email: String): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"TransportStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private class Fixture(
        val teacher: UserEntity,
        val teacherToken: String,
        val clazz: TeacherClassEntity,
        val student: AuthResponse,
        val studentUser: UserEntity,
        val outsider: AuthResponse,
        val parent: UserEntity,
        val parentToken: String,
    )

    private fun fixture(): Fixture {
        val teacher = newUser("0779700000", "transport.teacher@test", Role.TEACHER)
        val teacherToken = login("transport.teacher@test")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Form 4 South"
            gradeLevel = "Form 4"
            subject = "Science"
        })
        val student = signup("0779700001")
        val studentUser = userRepository.findById(java.util.UUID.fromString(student.user.id)).orElseThrow()
        membershipRepository.save(ClassMembershipEntity().apply {
            classId = clazz.id
            studentId = studentUser.id
        })
        val outsider = signup("0779700002")
        val parent = newUser("0779700003", "transport.parent@test", Role.PARENT)
        val parentToken = login("transport.parent@test")
        studentUser.parentUserId = parent.id
        userRepository.save(studentUser)
        return Fixture(teacher, teacherToken, clazz, student, studentUser, outsider, parent, parentToken)
    }

    private fun createGroup(f: Fixture): ClassGroupPayload {
        val body = objectMapper.writeValueAsString(listOf(f.studentUser.id.toString()))
        val response = mockMvc.perform(
            post("/teacher/class-groups?teacherId=${f.teacher.id}&name=Form 4 South&classId=${f.clazz.id}")
                .header("Authorization", auth(f.teacherToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ClassGroupPayload::class.java)
    }

    @Test
    fun `student and parent transport with idempotent sends`() {
        val f = fixture()
        val group = createGroup(f)

        // student sees the class community and posts (idempotent replay)
        val groups = objectMapper.readValue(
            mockMvc.perform(get("/student/class-groups").header("Authorization", auth(f.student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ClassGroupPayload>::class.java,
        )
        check(groups.single().id == group.id)

        val first = objectMapper.readValue(
            mockMvc.perform(
                post("/student/group/${group.id}/messages?text=Revision tomorrow&clientMessageId=cm_1")
                    .header("Authorization", auth(f.student.sessionToken!!))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupMessagePayload::class.java,
        )
        check(first.senderRole == "STUDENT")
        val replay = objectMapper.readValue(
            mockMvc.perform(
                post("/student/group/${group.id}/messages?text=Revision tomorrow&clientMessageId=cm_1")
                    .header("Authorization", auth(f.student.sessionToken!!))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupMessagePayload::class.java,
        )
        check(replay.id == first.id)

        // teacher receives a fan-out notification with the chat deep link
        val notices = objectMapper.readValue(
            mockMvc.perform(get("/notifications?userId=${f.teacher.id}").header("Authorization", auth(f.teacherToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<AppNotificationPayload>::class.java,
        )
        val chatNotice = notices.first { it.type == "MESSAGE" }
        check(chatNotice.actionRoute!!.startsWith("class_group_chat/"))

        // parent sees the child's community, posts as PARENT and clears unread
        val parentGroups = objectMapper.readValue(
            mockMvc.perform(get("/parent/child/${f.studentUser.id}/class-groups").header("Authorization", auth(f.parentToken)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ClassGroupPayload>::class.java,
        )
        check(parentGroups.single().id == group.id)
        val parentMessage = objectMapper.readValue(
            mockMvc.perform(
                post("/parent/group/${group.id}/messages?text=Thank you teacher")
                    .header("Authorization", auth(f.parentToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupMessagePayload::class.java,
        )
        check(parentMessage.senderRole == "PARENT")
        mockMvc.perform(post("/parent/group/${group.id}/read").header("Authorization", auth(f.parentToken)))
            .andExpect(status().isNoContent)

        val afterRead = objectMapper.readValue(
            mockMvc.perform(get("/parent/child/${f.studentUser.id}/class-groups").header("Authorization", auth(f.parentToken)))
                .andReturn().response.contentAsString,
            Array<ClassGroupPayload>::class.java,
        )
        check(afterRead.single().unreadCount == 0)
    }

    @Test
    fun `announcement mode mute and membership gating`() {
        val f = fixture()
        val group = createGroup(f)

        // a student outside the class cannot see or read the group
        check(objectMapper.readValue(
            mockMvc.perform(get("/student/class-groups").header("Authorization", auth(f.outsider.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ClassGroupPayload>::class.java,
        ).isEmpty())
        mockMvc.perform(get("/student/group/${group.id}/messages").header("Authorization", auth(f.outsider.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/parent/child/${f.outsider.user.id}/class-groups").header("Authorization", auth(f.parentToken)))
            .andExpect(status().isForbidden)

        // mute blocks the member's send
        mockMvc.perform(
            post("/teacher/class-groups/${group.id}/members/${f.studentUser.id}/mute?durationMinutes=60")
                .header("Authorization", auth(f.teacherToken))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/student/group/${group.id}/messages?text=blocked")
                .header("Authorization", auth(f.student.sessionToken!!))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/teacher/class-groups/${group.id}/members/${f.studentUser.id}/mute?durationMinutes=0")
                .header("Authorization", auth(f.teacherToken))
        ).andExpect(status().isNoContent)

        // announcement mode is teacher-only posting
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/teacher/class-groups/${group.id}?isAnnouncementMode=true")
                    .header("Authorization", auth(f.teacherToken))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupPayload::class.java,
        )
        check(updated.isAnnouncementMode)
        mockMvc.perform(
            post("/student/group/${group.id}/messages?text=not allowed")
                .header("Authorization", auth(f.student.sessionToken!!))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/teacher/class-groups/${group.id}/messages?text=Announcement")
                .header("Authorization", auth(f.teacherToken))
        ).andExpect(status().isOk)
    }

    @Test
    fun `attachments and poll voting for students and parents`() {
        val f = fixture()
        val group = createGroup(f)

        val pdf = MockMultipartFile("file", "handout.pdf", "application/pdf", "%PDF-1.4".toByteArray())
        val attachment = objectMapper.readValue(
            mockMvc.perform(
                multipart("/student/group/${group.id}/attachments").file(pdf)
                    .header("Authorization", auth(f.student.sessionToken!!))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MessageAttachmentPayload::class.java,
        )
        check(attachment.type == "PDF")
        check(attachment.fileName == "handout.pdf")

        val poll = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/class-groups/${group.id}/polls?question=Extra session?")
                    .header("Authorization", auth(f.teacherToken))
                    .contentType(MediaType.APPLICATION_JSON).content("""["Thursday","Friday"]""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            LivePollPayload::class.java,
        )
        mockMvc.perform(
            post("/student/group/polls/${poll.id}/vote?optionIndex=1").header("Authorization", auth(f.student.sessionToken!!))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/student/group/polls/${poll.id}/vote?optionIndex=9").header("Authorization", auth(f.student.sessionToken!!))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/parent/group/polls/${poll.id}/vote?optionIndex=0").header("Authorization", auth(f.parentToken))
        ).andExpect(status().isNoContent)

        // role mismatch: a student token cannot use the parent surface
        mockMvc.perform(get("/parent/child/${f.studentUser.id}/class-groups").header("Authorization", auth(f.student.sessionToken!!)))
            .andExpect(status().isForbidden)
    }
}
