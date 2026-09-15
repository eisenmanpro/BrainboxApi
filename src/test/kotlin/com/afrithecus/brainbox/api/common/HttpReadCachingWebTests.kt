package com.afrithecus.brainbox.api.common

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.web.CreateContentRequest
import com.afrithecus.brainbox.api.learning.web.CreatePostRequest
import com.afrithecus.brainbox.api.learning.web.CreateReadableRequest
import com.afrithecus.brainbox.api.learning.web.LearningPostPayload
import com.afrithecus.brainbox.api.learning.web.ReadableFilePayload
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger

/**
 * H1 origin read caching. With no third-party CDN (data sovereignty), the three
 * learner content reads must revalidate at the origin: a first GET returns a
 * strong ETag and private cache policy, a GET with the matching If-None-Match
 * returns 304 with no body, a GET without the header returns the body again, and
 * an unauthenticated GET is still rejected before any cache decision.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HttpReadCachingWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun adminToken(): String {
        val n = nextAdmin.incrementAndGet()
        val email = "admin" + n + "@h1.cache.test"
        userRepository.save(
            UserEntity().apply {
                phoneNumber = "0796" + (900 + n)
                this.email = email
                passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
                name = "H1 Admin"
                role = Role.ADMIN
                isVerified = true
                isActive = true
            }
        )
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"password\":\"adminpass123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun signup(phone: String): AuthResponse {
        val body = "{\"name\":\"H1 Student " + phone + "\",\"phoneNumber\":\"" + phone +
            "\",\"password\":\"password123\",\"role\":\"STUDENT\"}"
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "h1-device")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun createPost(admin: String): String {
        val request = CreatePostRequest(
            title = "H1 Cached Notes",
            subject = "MATHEMATICS",
            scope = "GLOBAL",
            gradeLevel = null,
            isFeatured = false,
            content = listOf(
                CreateContentRequest(type = "NOTES", title = "Intro", content = "cached notes body"),
            ),
        )
        val response = mockMvc.perform(
            post("/admin/learning/posts").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, LearningPostPayload::class.java).id
    }

    private fun createReadable(admin: String): String {
        val request = CreateReadableRequest(
            title = "H1 Cached Material",
            subject = "Mathematics",
            fileUrl = "h1-cached-material.txt",
            fileType = "TXT",
            scope = "GLOBAL",
        )
        val response = mockMvc.perform(
            post("/admin/materials/readable").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ReadableFilePayload::class.java).id
    }

    private fun createPastPaper(admin: String): String {
        val request = CreateExamRequest(
            title = "H1 Cached Paper",
            subject = "Mathematics",
            examType = "PAST_PAPER",
            durationMinutes = 60,
            examYear = 2024,
            questions = listOf(
                CreateExamQuestionRequest(
                    text = "2 + 2?", type = "MCQ", options = listOf("3", "4"),
                    correctAnswer = "4", explanation = "sum", points = 2, topic = "Algebra",
                ),
            ),
        )
        val response = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ExamDetail::class.java).id
    }

    /** First GET -> ETag + cache policy; conditional GET -> 304; plain GET -> body; no token -> 401. */
    private fun assertOriginCached(path: String, token: String) {
        val first = mockMvc.perform(get(path).header("Authorization", auth(token)))
            .andExpect(status().isOk)
            .andExpect(header().exists("ETag"))
            .andExpect(header().string("Cache-Control", "max-age=60, must-revalidate, private"))
            .andReturn()
        val etag = requireNotNull(first.response.getHeader("ETag")) { "no ETag on " + path }
        val body = first.response.contentAsString
        check(body.isNotBlank()) { "empty body on " + path }

        val conditional = mockMvc.perform(
            get(path).header("Authorization", auth(token)).header("If-None-Match", etag)
        ).andExpect(status().isNotModified)
            .andExpect(header().exists("ETag"))
            .andReturn()
        check(conditional.response.contentAsString.isEmpty()) { "304 body not empty on " + path }
        check(conditional.response.contentAsByteArray.isEmpty()) { "304 bytes not empty on " + path }

        // Same representation without the conditional header: full body and the same ETag.
        val second = mockMvc.perform(get(path).header("Authorization", auth(token)))
            .andExpect(status().isOk)
            .andReturn()
        check(second.response.getHeader("ETag") == etag) { "ETag changed on " + path }
        check(second.response.contentAsString == body) { "body changed on " + path }

        // Auth is enforced before the cache decision: no token, no 200/304.
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `learner content reads carry etag and revalidate at the origin`() {
        val admin = adminToken()
        val student = signup("0796500001")
        val token = student.sessionToken!!
        val postId = createPost(admin)
        val fileId = createReadable(admin)
        val paperId = createPastPaper(admin)

        assertOriginCached("/learning/post/" + postId + "/content", token)
        assertOriginCached("/materials/readable/" + fileId, token)
        assertOriginCached("/past-papers/" + paperId + "/content", token)
    }

    private companion object {
        val nextAdmin = AtomicInteger(0)
    }
}
