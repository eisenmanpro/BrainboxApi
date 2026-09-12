package com.afrithecus.brainbox.api.homework

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.homework.web.HomeworkPayload
import com.afrithecus.brainbox.api.homework.web.HomeworkUpsertRequest
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.media.MediaUploadResponsePayload
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Homework attachments + past-paper linking (web homework contract): the
 * PAST_PAPER_REVIEW type must carry relatedPaperCode, and submission attachments
 * upload through the shared media endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HomeworkAttachmentWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val classRepository: TeacherClassRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun newUser(phone: String, email: String, role: Role): UserEntity =
        userRepository.save(UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            name = "Attach " + phone
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
        val body = """{"name":"AttachStudent ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun homeworkRequest(teacher: UserEntity, classId: String, id: String, paperCode: String?): HomeworkUpsertRequest =
        HomeworkUpsertRequest(
            id = id,
            classId = classId,
            title = "Past paper review",
            description = "Review the attached past paper and answer the questions.",
            subject = "Mathematics",
            gradeLevel = 9,
            dueDate = System.currentTimeMillis() + 86_400_000,
            submissionType = "PAST_PAPER_REVIEW",
            relatedPaperCode = paperCode,
            relatedDocumentId = "doc_2024_1",
            scope = "SCHOOL_GRADE_CLASS",
            isDraft = false,
        )

    @Test
    fun `past paper review requires a paper code and round-trips it`() {
        val teacher = newUser("0779500000", "attach.teacher@test", Role.TEACHER)
        val token = login("attach.teacher@test")
        val clazz = classRepository.save(TeacherClassEntity().apply {
            teacherUserId = teacher.id
            name = "Form 3 East"
            gradeLevel = "Form 3"
            subject = "Mathematics"
        })

        // missing paper code is rejected
        mockMvc.perform(
            post("/teacher/homework").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(homeworkRequest(teacher, clazz.id.toString(), "hw_1", null)))
        ).andExpect(status().isBadRequest)

        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/teacher/homework").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(homeworkRequest(teacher, clazz.id.toString(), "hw_2", "PP-2024-MATH")))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            HomeworkPayload::class.java,
        )
        check(created.relatedPaperCode == "PP-2024-MATH")
        check(created.relatedDocumentId == "doc_2024_1")
    }

    @Test
    fun `submission attachments upload and are served`() {
        newUser("0779500010", "attach.teacher2@test", Role.TEACHER)
        val student = signup("0779500011")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val file = MockMultipartFile("file", "note.png", "image/png", bytes)

        val uploaded = objectMapper.readValue(
            mockMvc.perform(
                multipart("/homework/attachments").file(file)
                    .header("Authorization", auth(student.sessionToken!!))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            MediaUploadResponsePayload::class.java,
        )
        check(uploaded.mediaType == "IMAGE")
        check(uploaded.url.isNotBlank())

        // the hosted URL resolves to the stored file
        val path = URI(uploaded.url).path
        mockMvc.perform(get(path)).andExpect(status().isOk)

        // documents/plain text are accepted (api_homework_changes.md attachment guard)
        val text = MockMultipartFile("file", "note.txt", "text/plain", "hello".toByteArray())
        mockMvc.perform(
            multipart("/homework/attachments").file(text)
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)

        // unsupported binaries are rejected
        val bad = MockMultipartFile("file", "note.zip", "application/zip", byteArrayOf(1, 2, 3))
        mockMvc.perform(
            multipart("/homework/attachments").file(bad)
                .header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isBadRequest)

        mockMvc.perform(multipart("/homework/attachments").file(file)).andExpect(status().isUnauthorized)
    }
}
