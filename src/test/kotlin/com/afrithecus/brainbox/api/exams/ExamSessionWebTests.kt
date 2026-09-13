package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.DocumentItem
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.exams.web.ExamResultPayload
import com.afrithecus.brainbox.api.exams.web.ExamSessionResponse
import com.afrithecus.brainbox.api.exams.web.ExamSubmissionDetailsPayload
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

/**
 * End-to-end exam session lifecycle (doc 02 §3) + past-paper attempt recording
 * (doc 02 §5): start w/ withheld keys, sync idempotency, server grading,
 * results, re-submission rejection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExamSessionWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"User ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun adminToken(): String {
        val admin = userRepository.findByEmail("admin@session.test") ?: UserEntity().apply {
            phoneNumber = "0799000200"
            email = "admin@session.test"
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Session Admin"
            role = Role.ADMIN
            isVerified = true
            isActive = true
        }.also { userRepository.save(it) }
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"admin@session.test","password":"adminpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun seedDigitalExam(): String {
        val request = CreateExamRequest(
            title = "Session Maths", subject = "Mathematics", examType = "DIGITAL",
            durationMinutes = 10, difficulty = 2,
            questions = listOf(
                CreateExamQuestionRequest(
                    text = "2 + 2?", type = "MCQ", options = listOf("3", "4", "5"),
                    correctAnswer = "4", explanation = "basic sum", points = 2,
                ),
                CreateExamQuestionRequest(
                    text = "Pick A and C", type = "MULTI_SELECT", options = listOf("A", "B", "C", "D"),
                    correctAnswer = """["A","C"]""", explanation = "both", points = 2,
                ),
                CreateExamQuestionRequest(
                    text = "Match them", type = "MATCHING", points = 1,
                    matchingPairs = mapOf("a" to "1", "b" to "2"),
                    explanation = "pairs",
                ),
            ),
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ExamDetail::class.java).id
    }

    @Test
    fun `full lifecycle start sync submit result with server grading`() {
        val student = signup("0770000001")
        val examId = seedDigitalExam()
        val token = student.sessionToken!!

        // start -> keys withheld
        val startBody = mockMvc.perform(
            get("/exams/${examId}/session").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(!startBody.contains("correctAnswer"))
        check(!startBody.contains("explanation"))
        val session = objectMapper.readValue(startBody, ExamSessionResponse::class.java)
        check(session.status == "IN_PROGRESS")
        check(session.questions.size == 3)
        check(session.timeRemainingSeconds > 0)
        val qids = session.questions.map { it.id }

        // resume returns the same session
        val resume = mockMvc.perform(
            get("/exams/${examId}/session").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(resume, ExamSessionResponse::class.java).id == session.id)

        // sync persists progress and is idempotent
        val syncBody = objectMapper.writeValueAsString(
            mapOf(
                "answers" to mapOf(qids[0] to "4"),
                "currentQuestionIndex" to 1,
                "flaggedQuestions" to listOf(qids[1]),
            )
        )
        mockMvc.perform(
            post("/exams/${examId}/session/sync").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(syncBody)
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/exams/${examId}/session/sync").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(syncBody)
        ).andExpect(status().isOk)

        val synced = mockMvc.perform(
            get("/exams/${examId}/session").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val syncedSession = objectMapper.readValue(synced, ExamSessionResponse::class.java)
        check(syncedSession.currentQuestionIndex == 1)
        check(syncedSession.flaggedQuestions?.contains(qids[1]) == true)
        check(syncedSession.answers != null)

        // submit: q1 correct (2), q2 correct (2), q3 correct (1) => 5/5
        val submitBody = objectMapper.writeValueAsString(
            mapOf(qids[0] to "4", qids[1] to listOf("A", "C"), qids[2] to mapOf("a" to "1", "b" to "2"))
        )
        val resultBody = mockMvc.perform(
            post("/exams/${examId}/session/submit").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(resultBody, ExamResultPayload::class.java)
        check(result.score == 5)
        check(result.totalPoints == 5)
        check(result.percentage == 100.0)
        check(result.title == "Session Maths")
        check(result.status == "PUBLISHED")
        check(result.markingType == "AUTOMATIC")
        check(result.autoGradedScore == 5)
        check(result.pendingReviewScore == 0)
        check(result.percentile == 100)
        check(result.gradingDetails.size == 3)
        check(result.gradingDetails.count { it.isCorrect } == 3)
        // Key material must never reach the student, not even after grading.
        check(!resultBody.contains("correctAnswer"))
        check(!resultBody.contains("explanation"))

        // result endpoint returns the same result; resubmission is rejected
        val result2 = mockMvc.perform(
            get("/exams/${examId}/result").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(result2, ExamResultPayload::class.java).score == 5)

        // Resubmission is idempotent per (examId, userId): the same answers return
        // the stored result instead of rejecting or double-grading.
        val replayed = mockMvc.perform(
            post("/exams/${examId}/session/submit").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(replayed, ExamResultPayload::class.java).score == 5)
    }

    @Test
    fun `wrong answers are graded by the server and partial marks are exact`() {
        val student = signup("0770000002")
        val examId = seedDigitalExam()
        val token = student.sessionToken!!

        val startBody = mockMvc.perform(
            get("/exams/${examId}/session").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val qids = objectMapper.readValue(startBody, ExamSessionResponse::class.java).questions.map { it.id }

        // q1 wrong, q2 right, q3 wrong -> score 2/5
        val submitBody = objectMapper.writeValueAsString(
            mapOf(qids[0] to "5", qids[1] to listOf("A", "C"), qids[2] to mapOf("a" to "9", "b" to "2"))
        )
        val resultBody = mockMvc.perform(
            post("/exams/${examId}/session/submit").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(resultBody, ExamResultPayload::class.java)
        check(result.score == 2)
        check(result.percentage == 40.0)
        check(result.autoGradedScore == 2)
        check(result.gradingDetails.count { it.isCorrect } == 1)
    }

    @Test
    fun `submitting without a started session conflicts`() {
        val student = signup("0770000003")
        val examId = seedDigitalExam()
        mockMvc.perform(
            post("/exams/${examId}/session/submit").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"q":"4"}""")
        ).andExpect(status().isConflict)
    }

    @Test
    fun `past paper discovery and idempotent attempts`() {
        val student = signup("0770000004")
        val admin = adminToken()
        val request = CreateExamRequest(
            title = "KCSE 2024 Maths PP1", subject = "Mathematics", examType = "PAST_PAPER",
            durationMinutes = 120, examYear = 2024,
            questions = listOf(
                CreateExamQuestionRequest(text = "Q", type = "MCQ", options = listOf("A", "B"), correctAnswer = "A")
            ),
        )
        val body = objectMapper.writeValueAsString(request)
        val created = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val examId = objectMapper.readValue(created, ExamDetail::class.java).id

        val all = mockMvc.perform(
            get("/past-papers/all").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val listed = objectMapper.readValue(all, Array<DocumentItem>::class.java)
        val paper = listed.first { it.examYear == 2024 }
        // The Android DocumentItem needs a source descriptor, scope and timestamps.
        check(paper.source.type == "REMOTE")
        check(paper.scope == "GLOBAL" && paper.isPastPaper)
        check(paper.addedAt > 0)

        val search = mockMvc.perform(
            get("/past-papers/search?q=KCSE").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(search, Array<DocumentItem>::class.java).isNotEmpty())

        val attempt = """{"score":80,"totalPoints":100,"percentage":80,"submittedAt":${System.currentTimeMillis()}}"""
        mockMvc.perform(
            post("/past-papers/${examId}/attempts").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(attempt)
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/past-papers/${examId}/attempts").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(attempt)
        ).andExpect(status().isNoContent)


        // client-scored attempt is readable back as the exam result
        val resultBody = mockMvc.perform(
            get("/exams/${examId}/result").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val pastResult = objectMapper.readValue(resultBody, ExamResultPayload::class.java)
        check(pastResult.percentage == 80.0)
        check(pastResult.status == "PUBLISHED")
        check(pastResult.autoGradedScore == 80)
    }

    @Test
    fun `submission detail returns key-free questions and the student answers`() {
        val student = signup("0770000005")
        val examId = seedDigitalExam()
        val token = student.sessionToken!!

        val startBody = mockMvc.perform(
            get("/exams/${examId}/session").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val qids = objectMapper.readValue(startBody, ExamSessionResponse::class.java).questions.map { it.id }
        val submitBody = objectMapper.writeValueAsString(
            mapOf(qids[0] to "4", qids[1] to listOf("A", "C"), qids[2] to mapOf("a" to "1", "b" to "2"))
        )
        mockMvc.perform(
            post("/exams/${examId}/session/submit").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk)

        val body = mockMvc.perform(
            get("/exams/${examId}/submission").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val details = objectMapper.readValue(body, ExamSubmissionDetailsPayload::class.java)
        check(details.examId == examId)
        check(details.title == "Session Maths")
        check(details.questions.size == 3)
        check(details.userAnswers?.get(qids[0]) == "4")
        check(details.userAnswers?.get(qids[1]) == "A, C")
        check(details.status == "PUBLISHED")
        check(details.markingType == "AUTOMATIC")
        check(details.submittedAt > 0)
        check(!body.contains("correctAnswer"))
        check(!body.contains("explanation"))
    }

    @Test
    fun `submission before attempting an exam is not found`() {
        val student = signup("0770000006")
        val examId = seedDigitalExam()
        mockMvc.perform(
            get("/exams/${examId}/submission").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `essay exams report teacher review marking and pending review score`() {
        val student = signup("0770000007")
        val admin = adminToken()
        val request = CreateExamRequest(
            title = "Essay Paper", subject = "English", examType = "DIGITAL",
            durationMinutes = 30, difficulty = 3,
            questions = listOf(
                CreateExamQuestionRequest(
                    text = "Describe the theme", type = "ESSAY", correctAnswer = "expected",
                    explanation = "rubric", points = 10, topic = "Comprehension",
                ),
                CreateExamQuestionRequest(
                    text = "2 + 2?", type = "MCQ", options = listOf("3", "4"),
                    correctAnswer = "4", points = 2, topic = "Arithmetic",
                ),
            ),
        )
        val created = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val examId = objectMapper.readValue(created, ExamDetail::class.java).id

        val token = student.sessionToken!!
        val startBody = mockMvc.perform(
            get("/exams/${examId}/session").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val qids = objectMapper.readValue(startBody, ExamSessionResponse::class.java).questions.map { it.id }

        val submitBody = objectMapper.writeValueAsString(
            mapOf(qids[0] to "My essay answer", qids[1] to "4")
        )
        val resultBody = mockMvc.perform(
            post("/exams/${examId}/session/submit").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(resultBody, ExamResultPayload::class.java)
        check(result.markingType == "TEACHER_REVIEW")
        check(result.autoGradedScore == 2)
        check(result.pendingReviewScore == 10)
        check(result.score == 2)
        // Marking is not done, so the result is a stable PENDING, not a final score.
        check(result.status == "PENDING")
        val pending = objectMapper.readValue(
            mockMvc.perform(get("/exams/${examId}/result").header("Authorization", auth(token)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ExamResultPayload::class.java,
        )
        check(pending.status == "PENDING")
        check(result.gradingDetails.size == 2)
        check(result.gradingDetails.first { it.requiresExplanation }.isCorrect.not())
        check(result.topicBreakdown["Arithmetic"] == 1.0)
        check(result.topicBreakdown["Comprehension"] == 0.0)
        check(result.weakAreas.any { it.cbcStrand == "Comprehension" })

        val detailsBody = mockMvc.perform(
            get("/exams/${examId}/submission").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val details = objectMapper.readValue(detailsBody, ExamSubmissionDetailsPayload::class.java)
        check(details.markingType == "TEACHER_REVIEW")
        check(details.questions.size == 2)
        check(!detailsBody.contains("correctAnswer"))
        check(!detailsBody.contains("explanation"))
    }
}

