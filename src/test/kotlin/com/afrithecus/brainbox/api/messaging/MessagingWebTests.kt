package com.afrithecus.brainbox.api.messaging

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.web.CreateClassRequest
import com.afrithecus.brainbox.api.classes.web.TeacherClassPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.web.MessagePayload
import com.afrithecus.brainbox.api.messaging.web.SendMessageRequest
import com.afrithecus.brainbox.api.messaging.web.SchoolMemberPayload
import com.afrithecus.brainbox.api.messaging.web.TeacherSendMessageRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Messaging contract tests (doc 05 §2): direct authorization, teacher fan-out,
 * unified intendedForParent inbox, read receipts, idempotent message ids and the
 * school member directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MessagingWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signupStudent(phone: String, schoolName: String): AuthResponse {
        val body = """{"name":"Student ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT","schoolName":"${schoolName}"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun newTeacher(schoolId: String, phone: String): AuthResponse {
        val email = phone + "@msg.test"
        val teacher = UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("teacherpass123") ?: error("encode")
            name = "Msg Teacher"
            role = Role.TEACHER
            this.schoolId = UUID.fromString(schoolId)
            isActive = true
            isVerified = true
        }
        userRepository.save(teacher)
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"teacherpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java)
    }

    private fun auth(token: String) = "Bearer " + token

    private fun createClassWithRoster(teacher: AuthResponse, students: List<AuthResponse>): String {
        val req = CreateClassRequest(name = "Msg Class", grade = "Form 3", subject = "ENGLISH")
        val body = objectMapper.writeValueAsString(req)
        val resp = mockMvc.perform(
            post("/teacher/classes").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val clazz = objectMapper.readValue(resp, TeacherClassPayload::class.java)
        mockMvc.perform(
            post("/teacher/classes/${clazz.classId}/students").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"studentIds":[${students.joinToString { "\"" + it.user.id + "\"" }}]}""")
        ).andExpect(status().isNoContent)
        return clazz.classId
    }

    private fun inbox(user: AuthResponse): List<MessagePayload> {
        val body = mockMvc.perform(
            get("/messages/${user.user.id}?folder=inbox").header("Authorization", auth(user.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<MessagePayload>::class.java).toList()
    }

    private fun sent(user: AuthResponse): List<MessagePayload> {
        val body = mockMvc.perform(
            get("/messages/${user.user.id}?folder=sent").header("Authorization", auth(user.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<MessagePayload>::class.java).toList()
    }

    @Test
    fun `direct send authorization read receipts and teacher fan out`() {
        val host = signupStudent("0776000001", "Msg High")
        val schoolId = host.user.schoolId!!
        val teacher = newTeacher(schoolId, "0776777001")
        val student = signupStudent("0776000002", "Msg High")
        val student2 = signupStudent("0776000003", "Msg High")

        // student -> teacher allowed; student -> student forbidden
        val toTeacher = SendMessageRequest(recipientId = teacher.user.id, subject = "Hi", body = "Question about homework")
        mockMvc.perform(
            post("/messages/send").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(toTeacher))
        ).andExpect(status().isOk)

        val toStudent = SendMessageRequest(recipientId = student2.user.id, body = "hey")
        mockMvc.perform(
            post("/messages/send").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(toStudent))
        ).andExpect(status().isForbidden)

        check(sent(student).size == 1)

        // teacher INDIVIDUAL to a student -> inbox + read receipt
        val individual = TeacherSendMessageRequest(audienceType = "INDIVIDUAL", recipientId = student.user.id, body = "Read chapter 3")
        val sentRaw = mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(individual))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val delivered = objectMapper.readValue(sentRaw, MessagePayload::class.java)
        check(delivered.id.isNotBlank())

        // read receipt applies to the recipient's inbox copy
        val studentInboxBefore = inbox(student)
        val inboxMessage = studentInboxBefore.first { it.body == "Read chapter 3" }
        mockMvc.perform(
            post("/messages/${inboxMessage.id}/read").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNoContent)
        val studentInbox = inbox(student)
        check(studentInbox.any { it.body == "Read chapter 3" && it.isRead })
        check(sent(teacher).any { it.body == "Read chapter 3" })

        // teacher CLASS fan-out reaches the roster
        val classId = createClassWithRoster(teacher, listOf(student, student2))
        val fanOut = TeacherSendMessageRequest(audienceType = "CLASS", audienceId = classId, body = "Class announcement")
        mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(fanOut))
        ).andExpect(status().isOk)
        check(inbox(student).any { it.body == "Class announcement" })
        check(inbox(student2).any { it.body == "Class announcement" })

        // CLASS_PARENTS lands in the student inbox with intendedForParent
        val parents = TeacherSendMessageRequest(audienceType = "CLASS_PARENTS", audienceId = classId, body = "PTA notice")
        mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(parents))
        ).andExpect(status().isOk)
        check(inbox(student).any { it.body == "PTA notice" && it.intendedForParent })
    }

    @Test
    fun `fan out is idempotent on the client message id`() {
        val host = signupStudent("0776000004", "Msg High")
        val teacher = newTeacher(host.user.schoolId!!, "0776777002")
        val student = signupStudent("0776000005", "Msg High")
        val classId = createClassWithRoster(teacher, listOf(student))

        val fanOut = TeacherSendMessageRequest(audienceType = "CLASS", audienceId = classId, body = "Only once")
        val payload = objectMapper.writeValueAsString(fanOut)
        mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.sessionToken!!))
                .header("X-Message-Id", "msg_99_x")
                .contentType(MediaType.APPLICATION_JSON).content(payload)
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.sessionToken!!))
                .header("X-Message-Id", "msg_99_x")
                .contentType(MediaType.APPLICATION_JSON).content(payload)
        ).andExpect(status().isOk)

        check(inbox(student).count { it.body == "Only once" } == 1)
        check(sent(teacher).count { it.body == "Only once" } == 1)
    }

    @Test
    fun `school member directory filters by role and school`() {
        val host = signupStudent("0776000006", "Msg High")
        val schoolId = host.user.schoolId!!
        val teacher = newTeacher(schoolId, "0776777003")
        val otherSchool = signupStudent("0776000007", "Other High")

        val teachers = mockMvc.perform(
            get("/school/${schoolId}/members?role=TEACHER").header("Authorization", auth(host.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val members = objectMapper.readValue(teachers, Array<SchoolMemberPayload>::class.java)
        check(members.any { it.id == teacher.user.id })

        // another school's student cannot list members here
        mockMvc.perform(
            get("/school/${schoolId}/members").header("Authorization", auth(otherSchool.sessionToken!!))
        ).andExpect(status().isForbidden)
    }
}
