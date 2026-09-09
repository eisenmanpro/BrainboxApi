package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.ExamCard
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.exams.web.HubState
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Exam hub/catalog contract tests (doc 02 §2): scope filtering, hub state/list
 * tabs, listings, and the answer-key withholding obligation on student payloads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExamsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signup(phone: String, schoolName: String? = null): AuthResponse {
        val schoolPart = if (schoolName != null) ""","schoolName":"${schoolName}"""" else ""
        val body = """{"name":"User ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"${schoolPart}}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun adminToken(): String {
        val admin = UserEntity().apply {
            phoneNumber = "0799000100"
            email = "admin@exams.test"
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Exam Admin"
            role = Role.ADMIN
            isVerified = true
            isActive = true
        }
        userRepository.save(admin)
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"admin@exams.test","password":"adminpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun createExam(adminToken: String, request: CreateExamRequest): ExamDetail {
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ExamDetail::class.java)
    }

    private fun mcq(text: String, correct: String) = CreateExamQuestionRequest(
        text = text, type = "MCQ", options = listOf("A", "B", "C"),
        correctAnswer = correct, explanation = "why", points = 2, difficulty = 3,
    )

    @Test
    fun `hub respects school scope and state counts`() {
        val admin = adminToken()
        val alpha = signup("0762000001", schoolName = "Alpha School")
        val alphaSchoolId = alpha.user.schoolId!!

        createExam(
            admin,
            CreateExamRequest(
                title = "Global Mathematics", subject = "Mathematics", examType = "DIGITAL",
                durationMinutes = 30, difficulty = 2,
                questions = listOf(mcq("2+2?", "4")),
            ),
        )
        createExam(
            admin,
            CreateExamRequest(
                title = "Alpha Only Science", subject = "Science", examType = "DIGITAL",
                durationMinutes = 20, scope = "SCHOOL", schoolId = alphaSchoolId,
                questions = listOf(mcq("H2O?", "Water")),
            ),
        )
        createExam(
            admin,
            CreateExamRequest(
                title = "Pop Quiz", subject = "Science", examType = "QUIZ",
                durationMinutes = 5, questions = listOf(mcq("Colour of sky?", "Blue")),
            ),
        )

        val stateBody = mockMvc.perform(
            get("/exams/hub/state").header("Authorization", auth(alpha.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val state = objectMapper.readValue(stateBody, HubState::class.java)
        check(state.availableCount == 3) // global digital + alpha digital + quiz
        check(state.quizzesCount == 1)
        check(state.inProgressCount == 0)
        check(state.completedCount == 0)
        check(state.pastPapersCount == 0)
    }

    @Test
    fun `students from other schools do not see school scoped exams`() {
        val admin = adminToken()
        val alpha = signup("0762000002", schoolName = "Alpha School")
        val beta = signup("0762000003", schoolName = "Beta School")

        createExam(
            admin,
            CreateExamRequest(
                title = "Alpha Hidden", subject = "Science", examType = "DIGITAL",
                durationMinutes = 20, scope = "SCHOOL", schoolId = alpha.user.schoolId!!,
                questions = listOf(mcq("1+1?", "2")),
            ),
        )

        val alphaList = mockMvc.perform(
            get("/exams/hub/list?tab=available").header("Authorization", auth(alpha.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(alphaList, Array<ExamCard>::class.java).any { it.title == "Alpha Hidden" })

        val betaList = mockMvc.perform(
            get("/exams/hub/list?tab=available").header("Authorization", auth(beta.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(betaList, Array<ExamCard>::class.java).none { it.title == "Alpha Hidden" })
    }

    @Test
    fun `student detail strips answer keys but admin sees them`() {
        val admin = adminToken()
        val student = signup("0762000004", schoolName = "Alpha School")

        val created = createExam(
            admin,
            CreateExamRequest(
                title = "Keys Hidden", subject = "Biology", examType = "DIGITAL",
                durationMinutes = 15,
                questions = listOf(
                    CreateExamQuestionRequest(
                        text = "Photosynthesis?", type = "MCQ", options = listOf("X", "Y"),
                        correctAnswer = "X", explanation = "it happens", points = 1,
                        matchingPairs = mapOf("a" to "1"),
                    )
                ),
            ),
        )

        val studentDetail = mockMvc.perform(
            get("/exams/${created.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(!studentDetail.contains("correctAnswer"))
        check(!studentDetail.contains("explanation"))
        check(!studentDetail.contains("matchingPairs"))
        check(studentDetail.contains("Photosynthesis?"))
        check(objectMapper.readValue(studentDetail, ExamDetail::class.java).questions.single().correctAnswer == null)

        val adminDetail = mockMvc.perform(
            get("/admin/exams/${created.id}").header("Authorization", auth(admin))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(adminDetail.contains("correctAnswer"))
        check(objectMapper.readValue(adminDetail, ExamDetail::class.java).questions.single().correctAnswer == "X")
    }

    @Test
    fun `archived exams disappear for students and non admins cannot author`() {
        val admin = adminToken()
        val student = signup("0762000005")

        val created = createExam(
            admin,
            CreateExamRequest(
                title = "To Archive", subject = "Maths", examType = "DIGITAL",
                durationMinutes = 10, questions = listOf(mcq("2x?", "2")),
            ),
        )
        mockMvc.perform(
            delete("/admin/exams/${created.id}").header("Authorization", auth(admin))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            get("/exams/${created.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"nope","subject":"x","examType":"DIGITAL","durationMinutes":5,"questions":[{"text":"q","type":"MCQ","points":1}]}""")
        ).andExpect(status().isForbidden)
    }
}
