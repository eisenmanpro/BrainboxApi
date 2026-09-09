package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.web.CreatePostRequest
import com.afrithecus.brainbox.api.learning.web.CreateReadableRequest
import com.afrithecus.brainbox.api.learning.web.LearningPostPayload
import com.afrithecus.brainbox.api.learning.web.ReadableFilePayload
import com.afrithecus.brainbox.api.learning.web.ReadingProgressPayload
import com.afrithecus.brainbox.api.learning.web.RecommendationPayload
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reading materials, reading/learning progress and recommendations (doc 03 §3-§5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LearningProgressWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signup(phone: String, schoolName: String): AuthResponse {
        val body = """{"name":"User ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT","schoolName":"${schoolName}"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun adminToken(): String {
        val n = nextAdmin.incrementAndGet()
        val email = "admin" + n + "@progress.test"
        val admin = UserEntity().apply {
            phoneNumber = "0796" + (700 + n)
            this.email = email
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Progress Admin"
            role = Role.ADMIN
            isVerified = true
            isActive = true
        }
        userRepository.save(admin)
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"adminpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun createReadable(admin: String, title: String, scope: String = "GLOBAL", schoolId: String? = null): String {
        val request = CreateReadableRequest(
            title = title, subject = "MATHEMATICS", category = "Textbook", fileUrl = "https://cdn/files/" + title,
            fileType = "PDF", pageCount = 120, scope = scope, schoolId = schoolId,
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/materials/readable").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ReadableFilePayload::class.java).id
    }

    private fun createPost(admin: String, title: String): String {
        val request = CreatePostRequest(title = title, subject = "MATHEMATICS")
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/learning/posts").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, LearningPostPayload::class.java).id
    }

    @Test
    fun `readable files are scope filtered and reading progress is validated and upserted`() {
        val admin = adminToken()
        val alpha = signup("0773000001", "Alpha High")
        val beta = signup("0773000002", "Beta High")
        createReadable(admin, "Open Textbook")
        val schoolFile = createReadable(admin, "Alpha Handbook", scope = "SCHOOL", schoolId = alpha.user.schoolId!!)

        val alphaList = mockMvc.perform(
            get("/materials/readable").header("Authorization", auth(alpha.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val alphaTitles = objectMapper.readValue(alphaList, Array<ReadableFilePayload>::class.java).map { it.title }
        check(alphaTitles.contains("Alpha Handbook"))

        val betaList = mockMvc.perform(
            get("/materials/readable").header("Authorization", auth(beta.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val betaTitles = objectMapper.readValue(betaList, Array<ReadableFilePayload>::class.java).map { it.title }
        check(!betaTitles.contains("Alpha Handbook"))

        // valid upsert
        val okBody = mockMvc.perform(
            post("/materials/reading/progress").header("Authorization", auth(alpha.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"${schoolFile}","currentPage":12,"totalPages":120,"lastReadAt":${System.currentTimeMillis()}}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(okBody, ReadingProgressPayload::class.java).currentPage == 12)

        // invalid page exceeds total -> 400
        mockMvc.perform(
            post("/materials/reading/progress").header("Authorization", auth(alpha.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"${schoolFile}","currentPage":200,"totalPages":120,"lastReadAt":${System.currentTimeMillis()}}""")
        ).andExpect(status().isBadRequest)

        // reading session valid then listing
        val start = System.currentTimeMillis()
        mockMvc.perform(
            post("/materials/reading/session").header("Authorization", auth(alpha.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"${schoolFile}","startTime":${start},"endTime":${start + 120_000},"pagesRead":8}""")
        ).andExpect(status().isOk)

        val sessions = mockMvc.perform(
            get("/materials/reading/sessions/${schoolFile}").header("Authorization", auth(alpha.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(sessions, Array<com.afrithecus.brainbox.api.learning.web.ReadingSessionPayload>::class.java).size == 1)

        // invalid session ordering -> 400
        mockMvc.perform(
            post("/materials/reading/session").header("Authorization", auth(alpha.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"fileId":"${schoolFile}","startTime":${start + 5000},"endTime":${start},"pagesRead":1}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `learning progress upsert continue and recommendations are server derived`() {
        val admin = adminToken()
        val student = signup("0773000003", "Alpha High")
        val postId = createPost(admin, "Algebra Foundations")

        // -1 pending then a real score
        mockMvc.perform(
            post("/learning/progress").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"postId":"${postId}","quizScore":-1,"completed":false}""")
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/learning/progress").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"postId":"${postId}","quizScore":75,"completed":true,"answersJson":"{}"}""")
        ).andExpect(status().isOk)

        // out-of-range score rejected
        mockMvc.perform(
            post("/learning/progress").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"postId":"${postId}","quizScore":150}""")
        ).andExpect(status().isBadRequest)

        val continueBody = mockMvc.perform(
            get("/learning/continue/${student.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val items = objectMapper.readValue(continueBody, Array<com.afrithecus.brainbox.api.learning.web.ContinueLearningItem>::class.java)
        check(items.size == 1)
        check(items.single().progress == 100)

        // a fresh post to recommend (the completed one is excluded)
        createPost(admin, "Geometry Basics")

        // recommendations respond (personalized on scored subject)
        val recBody = mockMvc.perform(
            get("/learning/recommendations/${student.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val recs = objectMapper.readValue(recBody, Array<RecommendationPayload>::class.java)
        check(recs.isNotEmpty())
        check(recs.any { it.title == "Geometry Basics" })
        check(recs.all { it.priority in setOf("HIGH", "MEDIUM", "LOW") })

        // another user's path is forbidden
        val other = signup("0773000004", "Beta High")
        mockMvc.perform(
            get("/learning/continue/${other.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isForbidden)
    }

    private companion object {
        val nextAdmin = AtomicInteger(0)
    }
}
