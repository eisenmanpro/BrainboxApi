package com.afrithecus.brainbox.api.interview

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.interview.web.InterviewAnalyticsPayload
import com.afrithecus.brainbox.api.interview.web.InterviewAnswerPayload
import com.afrithecus.brainbox.api.interview.web.InterviewQuestionPayload
import com.afrithecus.brainbox.api.interview.web.InterviewResultPayload
import com.afrithecus.brainbox.api.interview.web.InterviewSessionPayload
import com.afrithecus.brainbox.api.interview.web.PastAttemptPayload
import com.afrithecus.brainbox.api.interview.web.StartInterviewRequest
import com.afrithecus.brainbox.api.interview.web.SubmitAnswerRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Mock interviews (doc 06 §2): question bank, server-side scoring, session state,
 * completion idempotency, history/analytics and emotion metadata storage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InterviewWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Interview ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun start(student: AuthResponse, mode: String = "Q_A", type: String = "UNIVERSITY_INTERVIEW"): InterviewSessionPayload {
        val request = StartInterviewRequest(type = type, userId = student.user.id, mode = mode)
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/interview/start").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, InterviewSessionPayload::class.java)
    }

    private fun submit(
        student: AuthResponse,
        sessionId: String,
        questionId: String,
        text: String,
        emotion: String? = null,
    ): InterviewAnswerPayload {
        val request = SubmitAnswerRequest(
            sessionId = sessionId,
            questionId = questionId,
            transcribedText = text,
            primaryEmotion = emotion,
            emotionConfidence = if (emotion != null) 0.9 else null,
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/interview/submit-answer").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, InterviewAnswerPayload::class.java)
    }

    private val goodAnswer = "I am a passionate and dedicated student with a strong academic background. " +
        "My goals are to study computer science. Firstly, I led a coding project at school. " +
        "Secondly, I improved our results significantly. For example, I built a small system. " +
        "In conclusion, I have valuable experience."

    @Test
    fun `question bank and advanced difficulty`() {
        val student = signup("0778500001")
        val body = mockMvc.perform(
            get("/interview/questions?type=UNIVERSITY_INTERVIEW&difficulty=1").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val questions = objectMapper.readValue(body, Array<InterviewQuestionPayload>::class.java)
        check(questions.size == 4)
        check(questions.all { it.type == "UNIVERSITY_INTERVIEW" })
        check(questions.first { it.order == 1 }.rubricType == "STAR")

        val advancedBody = mockMvc.perform(
            get("/interview/questions?type=UNIVERSITY_INTERVIEW&difficulty=3").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val advanced = objectMapper.readValue(advancedBody, Array<InterviewQuestionPayload>::class.java)
        check(advanced.first().text.contains("(Advanced)"))
        check(advanced.first { it.order == 1 }.minWords == questions.first { it.order == 1 }.minWords + 20)
        check(advanced.first { it.order == 1 }.keywords.contains("strategic"))

        mockMvc.perform(get("/interview/questions?type=NOPE").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `full session is scored server side and completes idempotently`() {
        val student = signup("0778500002")
        val session = start(student)
        check(session.questions.size == 4)
        val first = session.questions.first { it.order == 1 }

        val answer = submit(student, session.id, first.id, goodAnswer, emotion = "CONFIDENT")
        check(answer.keywordMatchCount >= 4)
        check(answer.structurePhrasesFound >= 3)
        check(answer.wordCount > 30)
        check(answer.score > 0)
        check(answer.feedback.isNotBlank())
        check(answer.rubricScore != null)
        check(answer.rubricScore!!.criteria.size == 4)
        check(answer.dictionScore >= 0)

        // resubmitting the same question updates in place rather than duplicating
        submit(student, session.id, session.questions.first { it.order == 2 }.id, "passionate background goals experience dedicated")
        submit(student, session.id, first.id, goodAnswer, emotion = "CONFIDENT")

        val resultBody = mockMvc.perform(
            post("/interview/complete?sessionId=${session.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(resultBody, InterviewResultPayload::class.java)
        check(result.answers.size == 2)
        check(result.overallScore > 0)

        // complete is idempotent
        mockMvc.perform(
            post("/interview/complete?sessionId=${session.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)

        // answers cannot be added after completion
        mockMvc.perform(
            post("/interview/submit-answer").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SubmitAnswerRequest(session.id, first.id, goodAnswer)))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `history and analytics aggregate sessions and emotions`() {
        val student = signup("0778500003")
        val session = start(student, mode = "SPEECH")
        submit(student, session.id, session.questions.first().id, goodAnswer, emotion = "NERVOUS")
        mockMvc.perform(
            post("/interview/complete?sessionId=${session.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk)

        val historyBody = mockMvc.perform(
            get("/interview/history/${student.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val history = objectMapper.readValue(historyBody, Array<PastAttemptPayload>::class.java)
        check(history.size == 1)
        check(history.single().mode == "SPEECH")
        check(history.single().score > 0)

        val analyticsBody = mockMvc.perform(
            get("/interview/analytics/${student.user.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val analytics = objectMapper.readValue(analyticsBody, InterviewAnalyticsPayload::class.java)
        check(analytics.totalSessions == 1)
        check(analytics.averageScore > 0)
        check(analytics.categoryPerformance.isNotEmpty())
        check(analytics.trendPoints.size == 1)
        check(analytics.emotionDistribution["NERVOUS"] == 1)
    }

    @Test
    fun `interviews are self scoped and validate input`() {
        val a = signup("0778500004")
        val b = signup("0778500005")
        mockMvc.perform(get("/interview/history/${b.user.id}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/interview/analytics/${b.user.id}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/interview/start").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(StartInterviewRequest("UNIVERSITY_INTERVIEW", b.user.id, "Q_A")))
        ).andExpect(status().isForbidden)

        val session = start(a)
        mockMvc.perform(
            post("/interview/submit-answer").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SubmitAnswerRequest(session.id, UUID.randomUUID().toString(), goodAnswer)))
        ).andExpect(status().isNotFound)
        mockMvc.perform(
            post("/interview/submit-answer").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SubmitAnswerRequest(UUID.randomUUID().toString(), session.questions.first().id, goodAnswer)))
        ).andExpect(status().isNotFound)
    }
}
