package com.afrithecus.brainbox.api.study

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.study.web.RecordStudySessionRequest
import com.afrithecus.brainbox.api.study.web.StudyInsightsPayload
import com.afrithecus.brainbox.api.study.web.StudySessionPayload
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

/**
 * Study tools (doc 03 §9): server-owned sessions, idempotent replay, validation
 * and server-computed insights.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudyWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    private fun auth(token: String) = "Bearer " + token

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Study ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    /** Fixed base so a replay sends byte-identical timestamps (idempotency check). */
    private val base = System.currentTimeMillis()

    private fun record(
        student: AuthResponse,
        subject: String,
        topic: String,
        startTime: Long,
        minutes: Long,
        focus: Int,
    ): StudySessionPayload {
        val request = RecordStudySessionRequest(
            userId = student.user.id,
            subject = subject,
            topic = topic,
            startTime = startTime,
            endTime = startTime + minutes * 60_000,
            focusScore = focus,
        )
        val response = mockMvc.perform(
            post("/study/sessions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, StudySessionPayload::class.java)
    }

    @Test
    fun `sessions are recorded and replayed idempotently`() {
        val student = signup("0779400001")
        val first = record(student, "Mathematics", "Algebra", base - 3_600_000, 60, 80)
        check(first.durationMinutes == 60)
        check(first.focusScore == 80)

        record(student, "Mathematics", "Geometry", base - 1_800_000, 30, 40)
        record(student, "Biology", "Cells", base - 900_000, 90, 70)

        val sessions = objectMapper.readValue(
            mockMvc.perform(get("/study/sessions/${student.user.id}").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<StudySessionPayload>::class.java,
        )
        check(sessions.size == 3)
        // newest first
        check(sessions.first().subject == "Biology")

        // replaying the same session is a no-op that returns the stored row
        val replay = record(student, "Mathematics", "Algebra", base - 3_600_000, 60, 80)
        check(replay.id == first.id)
        val after = objectMapper.readValue(
            mockMvc.perform(get("/study/sessions/${student.user.id}").header("Authorization", auth(student.sessionToken!!)))
                .andReturn().response.contentAsString,
            Array<StudySessionPayload>::class.java,
        )
        check(after.size == 3)
    }

    @Test
    fun `insights are computed from real sessions`() {
        val student = signup("0779400002")
        record(student, "Mathematics", "Algebra", base - 3_600_000, 60, 80)
        record(student, "Mathematics", "Geometry", base - 1_800_000, 30, 40)
        record(student, "Biology", "Cells", base - 900_000, 90, 70)

        val insights = objectMapper.readValue(
            mockMvc.perform(get("/study/insights/${student.user.id}").header("Authorization", auth(student.sessionToken!!)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            StudyInsightsPayload::class.java,
        )
        check(insights.totalStudyHours == 3.0)
        check(insights.averageSessionDuration == 60)
        check(insights.mostStudiedSubject == "Biology")
        check(insights.streakDays == 1)
        check(insights.weeklyGoal == 7)
        check(insights.weeklyProgress == 3)
        check(insights.recommendations.isNotEmpty())
    }

    @Test
    fun `validation and self scoping`() {
        val student = signup("0779400003")
        val other = signup("0779400004")

        val now = System.currentTimeMillis()
        fun body(userId: String, start: Long, end: Long, subject: String = "Science", focus: Int = 50): String =
            objectMapper.writeValueAsString(
                RecordStudySessionRequest(userId = userId, subject = subject, startTime = start, endTime = end, focusScore = focus)
            )

        mockMvc.perform(
            post("/study/sessions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body(student.user.id, now, now - 60_000))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/study/sessions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body(student.user.id, now, now + 60_000, subject = ""))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/study/sessions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body(student.user.id, now, now + 60_000, focus = 150))
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/study/sessions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body(student.user.id, now, now + 800 * 60_000L))
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/study/sessions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body(other.user.id, now, now + 60_000))
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/study/sessions/${other.user.id}").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/study/insights/${other.user.id}").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isForbidden)

        mockMvc.perform(get("/study/insights/${student.user.id}")).andExpect(status().isUnauthorized)
    }
}
