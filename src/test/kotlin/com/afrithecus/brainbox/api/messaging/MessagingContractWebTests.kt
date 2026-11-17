package com.afrithecus.brainbox.api.messaging

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.web.CreateClassRequest
import com.afrithecus.brainbox.api.classes.web.TeacherClassPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.web.MessagePayload
import com.afrithecus.brainbox.api.messaging.web.MessageRecipientPayload
import com.afrithecus.brainbox.api.messaging.web.SendMessageRequest
import com.afrithecus.brainbox.api.messaging.web.TeacherSendMessageRequest
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
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

/**
 * Findings2 NEW Message compose + Notifications contract: recipient eligibility and the
 * directory, teacher <-> admin delivery with a MESSAGE notification, conversation grouping
 * (thread read + reply joins the same thread) and SENT/DELIVERED/READ receipt semantics.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MessagingContractWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private data class Ctx(val auth: AuthResponse, val user: UserEntity)

    private fun signupStudent(phone: String, schoolName: String): AuthResponse {
        val body = "{\"name\":\"Student " + phone + "\",\"phoneNumber\":\"" + phone +
            "\",\"password\":\"password123\",\"role\":\"STUDENT\",\"schoolName\":\"" + schoolName + "\"}"
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun newStaff(schoolId: UUID, phone: String, name: String, subRole: SubRole? = null): Ctx {
        val email = phone + "@msgcontract.test"
        val entity = userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            role = Role.TEACHER
            this.subRole = subRole
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        })
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"password\":\"password123\"}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return Ctx(objectMapper.readValue(login, AuthResponse::class.java), entity)
    }

    private fun auth(token: String) = "Bearer " + token

    private fun createClass(teacher: AuthResponse, name: String, grade: String): String {
        val body = objectMapper.writeValueAsString(CreateClassRequest(name = name, grade = grade, subject = "ENGLISH"))
        val resp = mockMvc.perform(
            post("/teacher/classes").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(resp, TeacherClassPayload::class.java).classId
    }

    private fun addStudents(teacher: AuthResponse, classId: String, students: List<Ctx>) {
        mockMvc.perform(
            post("/teacher/classes/" + classId + "/students").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentIds\":[" + students.joinToString { "\"" + it.user.id + "\"" } + "]}"),
        ).andExpect(status().isNoContent)
    }

    private fun recipients(viewer: AuthResponse, query: String = ""): List<MessageRecipientPayload> {
        val body = mockMvc.perform(
            get("/messages/recipients" + query).header("Authorization", auth(viewer.sessionToken!!)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<MessageRecipientPayload>::class.java).toList()
    }

    private fun inbox(viewer: AuthResponse): List<MessagePayload> {
        val body = mockMvc.perform(
            get("/messages/" + viewer.user.id + "?folder=inbox").header("Authorization", auth(viewer.sessionToken!!)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<MessagePayload>::class.java).toList()
    }

    private fun sent(viewer: AuthResponse): List<MessagePayload> {
        val body = mockMvc.perform(
            get("/messages/" + viewer.user.id + "?folder=sent").header("Authorization", auth(viewer.sessionToken!!)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<MessagePayload>::class.java).toList()
    }

    private fun notifications(viewer: AuthResponse): List<AppNotificationPayload> {
        val body = mockMvc.perform(
            get("/notifications").param("userId", viewer.user.id).header("Authorization", auth(viewer.sessionToken!!)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<AppNotificationPayload>::class.java).toList()
    }

    @Test
    fun `recipient directory only offers eligible people and filters by role grade class and search`() {
        val host = signupStudent("0711100001", "Msg Contract High")
        val schoolId = UUID.fromString(host.user.schoolId!!)
        val teacherA = newStaff(schoolId, "0711100002", "Teacher A")
        val teacherB = newStaff(schoolId, "0711100003", "Teacher B")
        val admin = newStaff(schoolId, "0711100004", "ICT Admin", SubRole.ICT_ADMIN)
        val buffaloLearner = signupStudent("0711100005", "Msg Contract High")
        val lionLearner = signupStudent("0711100006", "Msg Contract High")
        val buffaloClass = createClass(teacherA.auth, "Buffalo", "Grade 5")
        val lionClass = createClass(teacherB.auth, "Lion", "Grade 5")
        addStudents(teacherA.auth, buffaloClass, listOf(Ctx(buffaloLearner, userRepository.findById(UUID.fromString(buffaloLearner.user.id)).orElseThrow())))
        addStudents(teacherB.auth, lionClass, listOf(Ctx(lionLearner, userRepository.findById(UUID.fromString(lionLearner.user.id)).orElseThrow())))

        val teacherView = recipients(teacherA.auth)
        val ids = teacherView.map { it.id }.toSet()
        check(ids.contains(teacherB.user.id.toString())) { "colleague missing" }
        check(ids.contains(admin.user.id.toString())) { "admin missing" }
        check(ids.contains(buffaloLearner.user.id)) { "own-class learner missing" }
        check(!ids.contains(lionLearner.user.id)) { "another class's learner leaked into the directory" }
        check(teacherView.first { it.id == admin.user.id.toString() }.relationship == "School admin")
        check(teacherView.first { it.id == buffaloLearner.user.id }.relationship == "Your class")

        // Role / class / search filters are server-side.
        check(recipients(teacherA.auth, "?role=TEACHER").all { it.role == "TEACHER" })
        val byClass = recipients(teacherA.auth, "?classId=" + buffaloClass)
        check(byClass.size == 1 && byClass.single().id == buffaloLearner.user.id)
        check(recipients(teacherA.auth, "?q=teacher b").any { it.id == teacherB.user.id.toString() })

        // The school admin may address everyone; a learner only staff.
        val adminIds = recipients(admin.auth).map { it.id }.toSet()
        check(adminIds.contains(lionLearner.user.id) && adminIds.contains(buffaloLearner.user.id))
        val learnerView = recipients(buffaloLearner)
        check(learnerView.isNotEmpty() && learnerView.all { it.role == "TEACHER" })
    }

    @Test
    fun `teacher to admin delivery creates a message notification and enforces eligibility`() {
        val host = signupStudent("0711200001", "Msg Notify High")
        val schoolId = UUID.fromString(host.user.schoolId!!)
        val teacher = newStaff(schoolId, "0711200002", "Notifying Teacher")
        val admin = newStaff(schoolId, "0711200003", "Notify Admin", SubRole.ICT_ADMIN)
        val otherClassLearner = signupStudent("0711200004", "Msg Notify High")
        val otherTeacher = newStaff(schoolId, "0711200005", "Other Teacher")
        val otherClass = createClass(otherTeacher.auth, "Far Class", "Grade 6")
        addStudents(otherTeacher.auth, otherClass, listOf(Ctx(otherClassLearner, userRepository.findById(UUID.fromString(otherClassLearner.user.id)).orElseThrow())))

        // teacher -> admin is allowed and actually lands in the admin inbox.
        val request = TeacherSendMessageRequest(audienceType = "INDIVIDUAL", recipientId = admin.user.id.toString(), subject = "Permission", body = "May I take the class out early?")
        mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.auth.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)),
        ).andExpect(status().isOk)
        val adminInbox = inbox(admin.auth)
        check(adminInbox.any { it.subject == "Permission" }) { "admin did not receive the teacher's message" }

        // The recipient's notifications feed carries a MESSAGE row deep-linking the thread.
        val adminNotifications = notifications(admin.auth)
        val messageNotification = adminNotifications.firstOrNull { it.type == "MESSAGE" }
        check(messageNotification != null) { "no MESSAGE notification for the received message" }
        check(messageNotification!!.title.contains("Notifying Teacher")) { "title=" + messageNotification.title }
        // Android's registered route is message_thread/{threadId}; the web normalises it.
        check(messageNotification.actionRoute!!.startsWith("message_thread/")) { "route=" + messageNotification.actionRoute }

        // A learner outside the teacher's classes is refused with actionable copy.
        val refused = mockMvc.perform(
            post("/messages/teacher/send").header("Authorization", auth(teacher.auth.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(TeacherSendMessageRequest(audienceType = "INDIVIDUAL", recipientId = otherClassLearner.user.id, body = "hi"))),
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(refused.contains("own classes")) { "message=" + refused }
    }

    @Test
    fun `a reply joins the conversation and receipts move from delivered to read`() {
        val host = signupStudent("0711300001", "Msg Thread High")
        val schoolId = UUID.fromString(host.user.schoolId!!)
        val teacher = newStaff(schoolId, "0711300002", "Thread Teacher")
        val learner = signupStudent("0711300003", "Msg Thread High")
        val learnerEntity = userRepository.findById(UUID.fromString(learner.user.id)).orElseThrow()
        val classId = createClass(teacher.auth, "Threader", "Grade 4")
        addStudents(teacher.auth, classId, listOf(Ctx(learner, learnerEntity)))

        val first = objectMapper.readValue(
            mockMvc.perform(
                post("/messages/teacher/send").header("Authorization", auth(teacher.auth.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(TeacherSendMessageRequest(audienceType = "INDIVIDUAL", recipientId = learner.user.id, subject = "Project", body = "Please bring your project."))),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MessagePayload::class.java,
        )
        check(first.threadId.isNotBlank() && first.status == "SENT") { "first=" + first.status }

        // The learner replies to that message; it must land in the same conversation.
        // A learner uses the generic send route (the teacher route is staff-only).
        val reply = objectMapper.readValue(
            mockMvc.perform(
                post("/messages/send").header("Authorization", auth(learner.sessionToken!!))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SendMessageRequest(recipientId = teacher.user.id.toString(), subject = "Re: Project", body = "Will do.", parentMessageId = first.id))),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MessagePayload::class.java,
        )
        check(reply.threadId == first.threadId) { "reply thread=" + reply.threadId + " expected=" + first.threadId }
        check(reply.parentMessageId == first.id)

        fun thread(viewer: AuthResponse): List<MessagePayload> {
            val body = mockMvc.perform(
                get("/messages/threads/" + first.threadId).header("Authorization", auth(viewer.sessionToken!!)),
            ).andExpect(status().isOk).andReturn().response.contentAsString
            return objectMapper.readValue(body, Array<MessagePayload>::class.java).toList()
        }
        // Each participant sees their own copy of each of the two messages: the send and the
        // reply (the thread read never returns the counterpart's duplicate copy).
        val teacherThread = thread(teacher.auth)
        check(teacherThread.size == 2) { "thread rows=" + teacherThread.size }
        check(teacherThread.first().body == "Please bring your project.")
        check(teacherThread.last().body == "Will do.")
        check(thread(learner).size == 2)

        // Delivery receipt: delivered once the inbox copy exists, read once it is opened.
        val teacherSent = sent(teacher.auth).first { it.id == first.id }
        check(teacherSent.status == "DELIVERED") { "status=" + teacherSent.status }
        val learnerInboxCopy = inbox(learner).first { it.id == teacherSent.id || it.body == "Please bring your project." }
        mockMvc.perform(
            post("/messages/" + learnerInboxCopy.id + "/read").header("Authorization", auth(learner.sessionToken!!)),
        ).andExpect(status().isNoContent)
        check(sent(teacher.auth).first { it.id == first.id }.status == "READ")
    }
}
