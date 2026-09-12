package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.web.ContentAnalyticsPayload
import com.afrithecus.brainbox.api.learning.web.MaterialUpdateRequest
import com.afrithecus.brainbox.api.learning.web.TeacherContentDraftPayload
import com.afrithecus.brainbox.api.learning.web.TeacherContentPayload
import com.afrithecus.brainbox.api.learning.web.TeacherDocumentPayload
import com.afrithecus.brainbox.api.learning.web.TeacherPostPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Teacher content management (docs/ongoing/api_content_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TeacherContentWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun teacher(phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@content.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        name = "Content Teacher"
        role = Role.TEACHER
        isActive = true
        isVerified = true
    })

    private fun learner(phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@content.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        name = "Content Learner"
        role = Role.STUDENT
        isActive = true
        isVerified = true
    })

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"${user.email}\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun <T> read(body: String, type: Class<T>): T = objectMapper.readValue(body, type)

    @Test
    fun `post, material upsert, publish, drafts and analytics`() {
        val teacher = teacher("0744000101")
        val token = token(teacher)
        val postId = UUID.randomUUID().toString()
        val materialId = UUID.randomUUID().toString()

        val post = TeacherPostPayload(
            id = postId, title = "Algebra notes", subject = "MATHEMATICS",
            gradeLevel = "Form 2", description = "Intro to algebra",
            tags = listOf("algebra"), scope = "SCHOOL_GRADE_CLASS",
        )
        read(
            mockMvc.perform(
                post("/teacher/content/post").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(post))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherPostPayload::class.java,
        )

        val content = TeacherContentPayload(
            id = materialId, postId = postId, type = "PDF", title = "Chapter 1",
            content = "https://cdn.brainbox.test/ch1.pdf", durationMinutes = 10, orderIndex = 0,
        )
        val saved = read(
            mockMvc.perform(
                put("/teacher/content/material/" + materialId).header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(MaterialUpdateRequest(post, content)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherContentPayload::class.java,
        )
        check(saved.type == "PDF")

        val listed = read(
            mockMvc.perform(get("/teacher/content").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherContentPayload>::class.java,
        )
        check(listed.size == 1 && listed.single().type == "PDF")
        check(
            read(
                mockMvc.perform(get("/teacher/content").param("type", "NOTES").header("Authorization", auth(token)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherContentPayload>::class.java,
            ).isEmpty()
        )

        // Publish flips the parent post.
        mockMvc.perform(
            post("/teacher/content/material/" + materialId + "/publish").header("Authorization", auth(token))
        ).andExpect(status().isOk)
        val posts = read(
            mockMvc.perform(get("/teacher/posts").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherPostPayload>::class.java,
        )
        check(posts.single().title == "Algebra notes")
        check(posts.single().isPublished)
        check(posts.single().authorName == "Content Teacher")

        val analytics = read(
            mockMvc.perform(get("/teacher/content/" + materialId + "/analytics").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ContentAnalyticsPayload::class.java,
        )
        check(analytics.views == 0 && analytics.engagementRate == 0.0)

        // Drafts upsert by client id and delete.
        val draft = TeacherContentDraftPayload(
            id = "draft_1", teacherId = teacher.id.toString(), type = "NOTES",
            title = "Draft", description = "d", tags = listOf("x"),
        )
        val savedDraft = read(
            mockMvc.perform(
                post("/teacher/content/drafts").header("Authorization", auth(token))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(draft))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherContentDraftPayload::class.java,
        )
        check(savedDraft.id == "draft_1")
        mockMvc.perform(
            post("/teacher/content/drafts").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(draft))
        ).andExpect(status().isOk)
        check(
            read(
                mockMvc.perform(get("/teacher/content/drafts").header("Authorization", auth(token)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherContentDraftPayload>::class.java,
            ).size == 1
        )
        mockMvc.perform(delete("/teacher/content/drafts/draft_1").header("Authorization", auth(token)))
            .andExpect(status().isNoContent)

        // Deleting the only material removes the now-empty post.
        mockMvc.perform(delete("/teacher/content/material/" + materialId).header("Authorization", auth(token)))
            .andExpect(status().isNoContent)
        check(
            read(
                mockMvc.perform(get("/teacher/posts").header("Authorization", auth(token)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherPostPayload>::class.java,
            ).isEmpty()
        )
    }

    @Test
    fun `documents upload idempotently, list and delete`() {
        val teacher = teacher("0744000102")
        val token = token(teacher)

        fun upload(id: String, type: String, body: ByteArray, fileName: String, contentType: String) = mockMvc.perform(
            multipart("/teacher/content/document")
                .file(MockMultipartFile("file", fileName, contentType, body))
                .file(MockMultipartFile("id", null, "text/plain", id.toByteArray()))
                .file(MockMultipartFile("title", null, "text/plain", "Chapter 1".toByteArray()))
                .file(MockMultipartFile("type", null, "text/plain", type.toByteArray()))
                .header("Authorization", auth(token))
        )

        val first = read(upload("doc_1", "PDF", byteArrayOf(0x25, 0x50, 0x44, 0x46), "ch1.pdf", "application/pdf")
            .andExpect(status().isOk).andReturn().response.contentAsString, TeacherDocumentPayload::class.java)
        check(first.id == "doc_1" && first.type == "PDF" && first.sourcePath.isNotBlank())

        // Replay of the same client id updates instead of duplicating.
        upload("doc_1", "PDF", byteArrayOf(0x25, 0x50, 0x44, 0x46), "ch1.pdf", "application/pdf")
            .andExpect(status().isOk)
        check(
            read(
                mockMvc.perform(get("/teacher/documents").header("Authorization", auth(token)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherDocumentPayload>::class.java,
            ).size == 1
        )

        // Unsupported document type is rejected.
        upload("doc_2", "DOCX", byteArrayOf(1, 2, 3), "ch.docx", "application/msword")
            .andExpect(status().isBadRequest)

        // Delete is repeat-safe.
        mockMvc.perform(delete("/teacher/content/document/doc_1").header("Authorization", auth(token)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/content/document/doc_1").header("Authorization", auth(token)))
            .andExpect(status().isNoContent)
        check(
            read(
                mockMvc.perform(get("/teacher/documents").header("Authorization", auth(token)))
                    .andExpect(status().isOk).andReturn().response.contentAsString,
                Array<TeacherDocumentPayload>::class.java,
            ).isEmpty()
        )
    }

    @Test
    fun `archive restore and scheduled publish control learner visibility`() {
        val teacher = teacher("0744000103")
        val student = learner("0744000104")
        val token = token(teacher)
        val studentToken = token(student)
        val postId = UUID.randomUUID().toString()
        val materialId = UUID.randomUUID().toString()

        val post = TeacherPostPayload(
            id = postId, title = "Photosynthesis", subject = "BIOLOGY",
            gradeLevel = "Form 2", description = "Notes", scope = "GLOBAL",
        )
        mockMvc.perform(post("/teacher/content/post").header("Authorization", auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(post)))
            .andExpect(status().isOk)
        val content = TeacherContentPayload(
            id = materialId, postId = postId, type = "NOTES", title = "Chapter 1",
            content = "Plants make food", durationMinutes = 10, orderIndex = 0,
        )
        mockMvc.perform(put("/teacher/content/material/" + materialId).header("Authorization", auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(MaterialUpdateRequest(post, content))))
            .andExpect(status().isOk)

        fun postStatus(): TeacherPostPayload = read(
            mockMvc.perform(get("/teacher/posts").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherPostPayload>::class.java,
        ).single()

        check(postStatus().status == "PUBLISHED")
        mockMvc.perform(get("/learning/post/" + postId).header("Authorization", auth(studentToken)))
            .andExpect(status().isOk)

        // Scheduled publish holds the post until its instant.
        val future = System.currentTimeMillis() + 3_600_000
        mockMvc.perform(post("/teacher/content/material/" + materialId + "/publish")
            .param("publishDate", future.toString()).header("Authorization", auth(token)))
            .andExpect(status().isOk)
        check(postStatus().status == "SCHEDULED")
        check(!postStatus().isPublished)
        mockMvc.perform(get("/learning/post/" + postId).header("Authorization", auth(studentToken)))
            .andExpect(status().isNotFound)

        // Immediate publish makes it visible.
        mockMvc.perform(post("/teacher/content/material/" + materialId + "/publish")
            .header("Authorization", auth(token)))
            .andExpect(status().isOk)
        check(postStatus().status == "PUBLISHED")
        mockMvc.perform(get("/learning/post/" + postId).header("Authorization", auth(studentToken)))
            .andExpect(status().isOk)

        // Archive hides it; restore brings it back. Both are repeat-safe.
        mockMvc.perform(post("/teacher/content/material/" + materialId + "/archive")
            .header("Authorization", auth(token)))
            .andExpect(status().isOk)
        check(postStatus().status == "ARCHIVED")
        mockMvc.perform(get("/learning/post/" + postId).header("Authorization", auth(studentToken)))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/teacher/content/material/" + materialId + "/archive")
            .header("Authorization", auth(token)))
            .andExpect(status().isOk)

        mockMvc.perform(delete("/teacher/content/material/" + materialId + "/archive")
            .header("Authorization", auth(token)))
            .andExpect(status().isOk)
        check(postStatus().status == "PUBLISHED")
        mockMvc.perform(get("/learning/post/" + postId).header("Authorization", auth(studentToken)))
            .andExpect(status().isOk)
    }
}

