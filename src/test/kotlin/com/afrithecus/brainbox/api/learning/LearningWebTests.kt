package com.afrithecus.brainbox.api.learning

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.web.CreateContentRequest
import com.afrithecus.brainbox.api.learning.web.CreatePostRequest
import com.afrithecus.brainbox.api.learning.web.LearningPostPayload
import com.afrithecus.brainbox.api.learning.web.QuizQuestionRequest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Learning hub contract tests (doc 03 §2): scope + grade filtering, quiz key
 * withholding on student payloads, view deduplication and search.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LearningWebTests(
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
        val email = "admin" + n + "@learning.test"
        val admin = UserEntity().apply {
            phoneNumber = "0797" + (800 + n)
            this.email = email
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Learning Admin"
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

    private fun setGrade(admin: String, student: AuthResponse, grade: String) {
        mockMvc.perform(
            patch("/admin/users/${student.user.id}").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"gradeLevel":"${grade}"}""")
        ).andExpect(status().isOk)
    }

    private fun createPost(
        admin: String,
        title: String,
        subject: String = "MATHEMATICS",
        scope: String = "GLOBAL",
        schoolId: String? = null,
        gradeLevel: String? = null,
        withQuiz: Boolean = true,
        featured: Boolean = false,
    ): String {
        val request = CreatePostRequest(
            title = title, subject = subject, scope = scope, schoolId = schoolId,
            gradeLevel = gradeLevel, isFeatured = featured,
            content = listOf(
                CreateContentRequest(type = "NOTES", title = "Intro", content = "some notes"),
                CreateContentRequest(
                    type = "QUIZ", title = "Check",
                    quizQuestions = listOf(
                        QuizQuestionRequest(
                            text = "2+2?", type = "MCQ", options = listOf("3", "4"),
                            correctAnswer = "4", explanation = "sum", points = 1,
                        )
                    ),
                ),
            ),
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/learning/posts").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, LearningPostPayload::class.java).id
    }

    @Test
    fun `scope and grade filtering control what students see`() {
        val admin = adminToken()
        val alpha = signup("0772000001", "Alpha High")
        val beta = signup("0772000002", "Beta High")
        setGrade(admin, alpha, "Form 3")
        setGrade(admin, beta, "Form 3")

        createPost(admin, "Global Notes", withQuiz = false, featured = true)
        createPost(admin, "Alpha Only", scope = "SCHOOL", schoolId = alpha.user.schoolId!!, gradeLevel = "Form 3")
        createPost(admin, "Form Four Only", scope = "SCHOOL", schoolId = alpha.user.schoolId!!, gradeLevel = "Form 4")

        val alphaFeatured = mockMvc.perform(
            get("/learning/featured").header("Authorization", auth(alpha.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val titles = objectMapper.readValue(alphaFeatured, Array<LearningPostPayload>::class.java).map { it.title }
        check(titles.contains("Global Notes"))

        // school + grade match visible in subject list; wrong grade hidden
        val alphaList = mockMvc.perform(
            get("/learning/subject/MATHEMATICS").header("Authorization", auth(alpha.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val alphaTitles = objectMapper.readValue(alphaList, Array<LearningPostPayload>::class.java).map { it.title }
        check(alphaTitles.contains("Alpha Only"))
        check(alphaTitles.contains("Global Notes"))
        check(!alphaTitles.contains("Form Four Only"))

        val betaList = mockMvc.perform(
            get("/learning/subject/MATHEMATICS").header("Authorization", auth(beta.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val betaTitles = objectMapper.readValue(betaList, Array<LearningPostPayload>::class.java).map { it.title }
        check(!betaTitles.contains("Alpha Only"))
        check(betaTitles.contains("Global Notes"))
    }

    @Test
    fun `student content strips quiz keys while admin keeps them`() {
        val admin = adminToken()
        val student = signup("0772000003", "Quiz High")
        val postId = createPost(admin, "Quiz Post", subject = "CHEMISTRY")

        val contentBody = mockMvc.perform(
            get("/learning/post/${postId}/content").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(!contentBody.contains("correctAnswer"))
        check(!contentBody.contains("explanation"))
        check(!contentBody.contains("matchingPairs"))
        check(contentBody.contains("2+2?"))

        val detailBody = mockMvc.perform(
            get("/learning/post/${postId}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(!detailBody.contains("correctAnswer"))

        val adminBody = mockMvc.perform(
            get("/admin/learning/posts/${postId}").header("Authorization", auth(admin))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(adminBody.contains("correctAnswer"))
    }

    @Test
    fun `views are deduplicated per user per day`() {
        val admin = adminToken()
        val student = signup("0772000004", "View High")
        val postId = createPost(admin, "Counted Post", withQuiz = false)

        mockMvc.perform(
            post("/learning/post/${postId}/view").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/learning/post/${postId}/view").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNoContent)

        val detail = mockMvc.perform(
            get("/learning/post/${postId}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(detail, LearningPostPayload::class.java).viewCount == 1)
    }

    @Test
    fun `search and unpublish`() {
        val admin = adminToken()
        val student = signup("0772000005", "Search High")
        val postId = createPost(admin, "Algebra Basics", subject = "MATHEMATICS", withQuiz = false)

        val found = mockMvc.perform(
            get("/learning/search?q=algebra").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(found, Array<LearningPostPayload>::class.java).any { it.title == "Algebra Basics" })

        mockMvc.perform(
            delete("/admin/learning/posts/${postId}").header("Authorization", auth(admin))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            get("/learning/post/${postId}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNotFound)
    }

    private companion object {
        val nextAdmin = AtomicInteger(0)
    }
}
