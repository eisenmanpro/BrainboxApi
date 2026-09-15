package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.ExamContentPayload
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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
 * Practice-paper content delivery (doc 02 §4.2): real cover metadata, a STANDARD
 * section with per-question keys inside markingScheme, and refusal to serve a
 * live digital exam whose keys must stay server-side.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PracticePaperContentWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun adminToken(): String {
        userRepository.save(UserEntity().apply {
            phoneNumber = "0799400000"
            email = "pp.content.admin@test"
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Content Admin"
            role = Role.ADMIN
            isActive = true
            isVerified = true
        })
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"pp.content.admin@test","password":"adminpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Content Student ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun seedExam(token: String, type: String, title: String): String {
        val request = CreateExamRequest(
            title = title,
            subject = "Mathematics",
            examType = type,
            durationMinutes = 60,
            examYear = 2024,
            questions = listOf(
                CreateExamQuestionRequest(
                    text = "2 + 2?", type = "MCQ", options = listOf("3", "4", "5"),
                    correctAnswer = "4", explanation = "basic sum", points = 2, topic = "Algebra",
                ),
                CreateExamQuestionRequest(
                    text = "Simplify 2x + 3x", type = "SHORT_ANSWER",
                    correctAnswer = "5x", explanation = "collect terms", points = 3, topic = "Algebra",
                ),
            ),
        )
        val response = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ExamDetail::class.java).id
    }

    @Test
    fun `practice paper content carries cover and marking scheme`() {
        val admin = adminToken()
        val paperId = seedExam(admin, "PRACTICE_PAPER", "KCSE 2024 Mathematics Paper 1")
        val student = signup("0779400010")

        val content = objectMapper.readValue(
            mockMvc.perform(get("/practice-papers/${paperId}/content").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamContentPayload::class.java,
        )
        check(content.examId == paperId)
        check(content.cover.subject == "Mathematics")
        check(content.cover.year == 2024)
        check(content.cover.questionCount == 2)
        check(content.cover.time == "1hr")
        check(content.cover.studentName == student.user.name)
        check(content.gradingMode == "AUTO_IMMEDIATE")

        check(content.sections.size == 1)
        val section = content.sections.single()
        check(section.type == "STANDARD")
        check(section.questions.size == 2)
        check(section.questions.first().number == 1)
        check(section.questions.first().correctAnswer == "4")
        check(section.questions.last().correctAnswer == "5x")

        check(content.markingScheme.totalMarks == 5)
        check(content.markingScheme.passingScore == 3)
        check(content.markingScheme.questionAnswers[section.questions.first().id] == "4")
        check(content.markingScheme.questionMarks[section.questions.last().id] == 3)
    }

    @Test
    fun `content refuses digital exams and unknown ids`() {
        val admin = adminToken()
        val digitalId = seedExam(admin, "DIGITAL", "Live Digital Exam")
        val student = signup("0779400011")

        // keys embedded in markingScheme must never be served for a live exam
        mockMvc.perform(get("/practice-papers/${digitalId}/content").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/practice-papers/not-a-uuid/content").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/practice-papers/${UUID.randomUUID()}/content").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isNotFound)
        mockMvc.perform(get("/practice-papers/${digitalId}/content")).andExpect(status().isUnauthorized)
    }
}
