package com.afrithecus.brainbox.api.classchat

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classchat.web.ClassGroupMessagePayload
import com.afrithecus.brainbox.api.classchat.web.ClassGroupPayload
import com.afrithecus.brainbox.api.classchat.web.GradebookContributionPayload
import com.afrithecus.brainbox.api.classchat.web.GroupTeacherPayload
import com.afrithecus.brainbox.api.classchat.web.MessageAttachmentPayload
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import com.afrithecus.brainbox.api.homework.model.HomeworkScope
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.model.SubmissionType
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.live.web.LivePollPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Teacher class-group chat (doc 04 §12): group CRUD, members, messages with
 * pin/unpin/delete, member mute, polls/votes, attachments, group teachers and
 * gradebook contributions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ClassChatWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val homeworkRepository: HomeworkRepository,
    @Autowired private val submissionRepository: HomeworkSubmissionRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Chat " + phone
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
        val body = """{"name":"ChatStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private class Fixture(
        val teacher: UserEntity,
        val token: String,
        val clazz: TeacherClassEntity,
        val studentA: UserEntity,
        val studentB: UserEntity,
    )

    private fun fixture(): Fixture {
        val teacher = newUser("0779600000", "chat.teacher@test", Role.TEACHER)
        val token = login("chat.teacher@test")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Form 3 North"
            gradeLevel = "Form 3"
            subject = "Science"
        })
        val studentA = newUser("0779600001", "chat.student.a@test", Role.STUDENT)
        val studentB = newUser("0779600002", "chat.student.b@test", Role.STUDENT)
        return Fixture(teacher, token, clazz, studentA, studentB)
    }

    private fun createGroup(f: Fixture, name: String = "Science Club"): ClassGroupPayload {
        val body = objectMapper.writeValueAsString(listOf(f.studentA.id.toString()))
        val response = mockMvc.perform(
            post("/teacher/class-groups?teacherId=${f.teacher.id}&name=${name}&classId=${f.clazz.id}")
                .header("Authorization", auth(f.token))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ClassGroupPayload::class.java)
    }

    @Test
    fun `group crud and membership`() {
        val f = fixture()
        val group = createGroup(f)
        check(group.memberCount == 1)
        check(group.teacherName == f.teacher.name)
        check(group.classId == f.clazz.id.toString())

        val list = objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups?teacherId=${f.teacher.id}").header("Authorization", auth(f.token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ClassGroupPayload>::class.java,
        )
        check(list.single().id == group.id)

        val members = objectMapper.writeValueAsString(listOf(f.studentA.id.toString(), f.studentB.id.toString()))
        val updated = objectMapper.readValue(
            mockMvc.perform(
                put("/teacher/class-groups/${group.id}?name=Science Club Renamed&description=After-school club")
                    .header("Authorization", auth(f.token))
                    .contentType(MediaType.APPLICATION_JSON).content(members)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupPayload::class.java,
        )
        check(updated.name == "Science Club Renamed")
        check(updated.description == "After-school club")
        check(updated.memberCount == 2)

        mockMvc.perform(delete("/teacher/class-groups/${group.id}").header("Authorization", auth(f.token)))
            .andExpect(status().isNoContent)
        check(objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups?teacherId=${f.teacher.id}").header("Authorization", auth(f.token)))
                .andReturn().response.contentAsString,
            Array<ClassGroupPayload>::class.java,
        ).isEmpty())
    }

    @Test
    fun `messages threading pinning and deletion`() {
        val f = fixture()
        val group = createGroup(f)

        val first = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/class-groups/${group.id}/messages?text=Welcome to the club")
                    .header("Authorization", auth(f.token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupMessagePayload::class.java,
        )
        check(first.senderRole == "TEACHER")
        check(first.senderId == f.teacher.id.toString())

        val reply = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/class-groups/${group.id}/messages?text=Bring your notebooks&replyTo=${first.id}")
                    .header("Authorization", auth(f.token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupMessagePayload::class.java,
        )
        check(reply.replyToId == first.id)

        // attachment-only message (blank text allowed when attachments exist)
        val withAttachment = objectMapper.writeValueAsString(
            listOf(MessageAttachmentPayload(url = "https://cdn.brainbox.com/x.png", type = "IMAGE"))
        )
        val attached = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/class-groups/${group.id}/messages?text=")
                    .header("Authorization", auth(f.token))
                    .contentType(MediaType.APPLICATION_JSON).content(withAttachment)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ClassGroupMessagePayload::class.java,
        )
        check(attached.attachments.single().type == "IMAGE")

        mockMvc.perform(
            post("/teacher/class-groups/${group.id}/messages?text=orphan&replyTo=${java.util.UUID.randomUUID()}|")
                .header("Authorization", auth(f.token))
        )

        val messages = objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups/${group.id}/messages?limit=10").header("Authorization", auth(f.token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<ClassGroupMessagePayload>::class.java,
        )
        check(messages.size == 3)

        mockMvc.perform(post("/teacher/class-groups/${group.id}/messages/${first.id}/pin").header("Authorization", auth(f.token)))
            .andExpect(status().isNoContent)
        val pinned = objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups/${group.id}/messages?limit=10").header("Authorization", auth(f.token)))
                .andReturn().response.contentAsString,
            Array<ClassGroupMessagePayload>::class.java,
        )
        check(pinned.first { it.id == first.id }.isPinned)
        mockMvc.perform(post("/teacher/class-groups/${group.id}/messages/${first.id}/unpin").header("Authorization", auth(f.token)))
            .andExpect(status().isNoContent)

        mockMvc.perform(delete("/teacher/class-groups/${group.id}/messages/${first.id}").header("Authorization", auth(f.token)))
            .andExpect(status().isNoContent)
        val after = objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups/${group.id}/messages?limit=10").header("Authorization", auth(f.token)))
                .andReturn().response.contentAsString,
            Array<ClassGroupMessagePayload>::class.java,
        )
        check(after.none { it.id == first.id })
    }

    @Test
    fun `mute polls and attachments`() {
        val f = fixture()
        val group = createGroup(f)

        mockMvc.perform(
            post("/teacher/class-groups/${group.id}/members/${f.studentA.id}/mute?durationMinutes=60")
                .header("Authorization", auth(f.token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/teacher/class-groups/${group.id}/members/${java.util.UUID.randomUUID()}/mute?durationMinutes=60")
                .header("Authorization", auth(f.token))
        ).andExpect(status().isNotFound)

        val poll = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/class-groups/${group.id}/polls?question=When should we meet?")
                    .header("Authorization", auth(f.token))
                    .contentType(MediaType.APPLICATION_JSON).content("""["Thursday","Friday"]""")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            LivePollPayload::class.java,
        )
        check(poll.options.size == 2)
        check(poll.votes.values.all { it == 0 })

        mockMvc.perform(
            post("/teacher/polls/${poll.id}/vote?optionIndex=1").header("Authorization", auth(f.token))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/teacher/polls/${poll.id}/vote?optionIndex=9").header("Authorization", auth(f.token))
        ).andExpect(status().isBadRequest)

        val pdf = MockMultipartFile("file", "notes.pdf", "application/pdf", "%PDF-1.4".toByteArray())
        val attachment = objectMapper.readValue(
            mockMvc.perform(
                multipart("/teacher/class-groups/${group.id}/attachments").file(pdf)
                    .header("Authorization", auth(f.token))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MessageAttachmentPayload::class.java,
        )
        check(attachment.type == "PDF")
        check(attachment.fileName == "notes.pdf")
        check(attachment.url.isNotBlank())
    }

    @Test
    fun `teachers gradebook and scoping`() {
        val f = fixture()
        val group = createGroup(f)

        val teachers = objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups/${group.id}/teachers").header("Authorization", auth(f.token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<GroupTeacherPayload>::class.java,
        )
        check(teachers.single().teacherId == f.teacher.id.toString())
        check(teachers.single().grade == 8)
        check(teachers.single().subject == "Science")

        // graded homework becomes a gradebook contribution
        homeworkRepository.save(HomeworkEntity().apply {
            id = "hw_chat_1"
            classId = f.clazz.id
            teacherId = f.teacher.id
            teacherName = f.teacher.name
            title = "Lab report"
            description = "Write up the lab."
            subject = "Science"
            gradeLevel = 9
            dueDate = Instant.now().minusSeconds(3600)
            submissionType = SubmissionType.FREE_TEXT
            scope = HomeworkScope.SCHOOL_GRADE_CLASS
            isActive = true
            isDraft = false
        })
        submissionRepository.save(HomeworkSubmissionEntity().apply {
            homeworkId = "hw_chat_1"
            studentId = f.studentA.id
            status = SubmissionStatus.GRADED
            grade = 88
            feedback = "Strong conclusions."
            submittedAt = Instant.now().minusSeconds(7200)
            gradedAt = Instant.now().minusSeconds(3600)
        })
        val contributions = objectMapper.readValue(
            mockMvc.perform(get("/teacher/class-groups/${group.id}/gradebook").header("Authorization", auth(f.token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<GradebookContributionPayload>::class.java,
        )
        check(contributions.single().assessmentType == "HOMEWORK")
        check(contributions.single().rawScore == 88)
        check(contributions.single().studentName == f.studentA.name)

        // another teacher cannot read the group
        val other = newUser("0779600010", "chat.teacher2@test", Role.TEACHER)
        val otherToken = login("chat.teacher2@test")
        mockMvc.perform(get("/teacher/class-groups/${group.id}/messages").header("Authorization", auth(otherToken)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            get("/teacher/class-groups?teacherId=${other.id}").header("Authorization", auth(f.token))
        ).andExpect(status().isForbidden)

        // students cannot use the teacher surface
        val student = signup("0779600011")
        mockMvc.perform(get("/teacher/class-groups?teacherId=${f.teacher.id}").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isForbidden)
    }
}
