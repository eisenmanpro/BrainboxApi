package com.afrithecus.brainbox.api.contests

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.contests.entity.ContestRegistrationEntity
import com.afrithecus.brainbox.api.contests.repository.ContestRegistrationRepository
import com.afrithecus.brainbox.api.contests.web.ContestPayload
import com.afrithecus.brainbox.api.contests.web.ContestResultPayload
import com.afrithecus.brainbox.api.contests.web.ContestSessionResponse
import com.afrithecus.brainbox.api.contests.web.CreateContestQuestionRequest
import com.afrithecus.brainbox.api.contests.web.CreateContestRequest
import com.afrithecus.brainbox.api.contests.web.LeaderboardPayload
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contest session, server-graded submit and leaderboard (doc 05 §1.5-§1.7).
 * Contests are seeded ONGOING (registration endpoint requires UPCOMING and is
 * covered separately), so registration rows are inserted directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContestsSessionWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val registrationRepository: ContestRegistrationRepository,
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
        val n = nextAdmin.incrementAndGet()
        val email = "admin" + n + "@csession.test"
        val admin = UserEntity().apply {
            phoneNumber = "0798" + (900 + n)
            this.email = email
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "CS Admin"
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

    private fun entitled(phone: String): AuthResponse {
        val student = signup(phone)
        val admin = adminToken()
        mockMvc.perform(
            post("/admin/users/${student.user.id}/approve").header("Authorization", auth(admin))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/admin/users/${student.user.id}/subscription").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"EXPLORER","expiryDate":${Instant.now().plusSeconds(2L * 24 * 3600).toEpochMilli()}}""")
        ).andExpect(status().isNoContent)
        return student
    }

    private fun seedOngoing(title: String): String {
        val now = Instant.now()
        val request = CreateContestRequest(
            title = title, subject = "General", grade = "Form 3",
            startTime = now.minusSeconds(3600).toEpochMilli(),
            endTime = now.plusSeconds(3600).toEpochMilli(),
            prize = "Prize",
            questions = listOf(
                CreateContestQuestionRequest(text = "2+2?", type = "MCQ", options = listOf("3", "4"), correctAnswer = "4", points = 2),
                CreateContestQuestionRequest(text = "3+3?", type = "MCQ", options = listOf("5", "6"), correctAnswer = "6", points = 3),
                CreateContestQuestionRequest(text = "1+1?", type = "MCQ", options = listOf("2", "3"), correctAnswer = "2", points = 1),
            ),
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/contests").header("Authorization", auth(adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ContestPayload::class.java).id
    }

    private fun registerAndStart(student: AuthResponse, contestId: String): ContestSessionResponse {
        val contestIdUuid = UUID.fromString(contestId)
        if (registrationRepository.findByStudentIdAndContestId(UUID.fromString(student.user.id), contestIdUuid) == null) {
            registrationRepository.save(
                ContestRegistrationEntity().apply {
                    studentId = UUID.fromString(student.user.id)
                    this.contestId = contestIdUuid
                }
            )
        }
        val body = mockMvc.perform(
            get("/contests/${contestId}/session").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, ContestSessionResponse::class.java)
    }

    @Test
    fun `registered student starts a key withheld session and syncs progress`() {
        val student = entitled("0781000001")
        val contestId = seedOngoing("Session Cup")
        val session = registerAndStart(student, contestId)

        check(session.status == "IN_PROGRESS")
        check(session.questions.size == 3)
        check(!objectMapper.writeValueAsString(session).contains("correctAnswer"))
        check(session.timeRemainingSeconds > 0)

        val qid = session.questions.first().id
        val sync = objectMapper.writeValueAsString(
            mapOf("answers" to mapOf(qid to "4"), "currentQuestionIndex" to 1)
        )
        mockMvc.perform(
            post("/contests/${contestId}/session/sync").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(sync)
        ).andExpect(status().isOk)

        val resumed = mockMvc.perform(
            get("/contests/${contestId}/session").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val resumedSession = objectMapper.readValue(resumed, ContestSessionResponse::class.java)
        check(resumedSession.currentQuestionIndex == 1)
        check(resumedSession.answers != null)
    }

    @Test
    fun `unregistered students cannot start and server grades the submit`() {
        val stranger = entitled("0781000002")
        val contestId = seedOngoing("Locked Cup")
        mockMvc.perform(
            get("/contests/${contestId}/session").header("Authorization", auth(stranger.sessionToken!!))
        ).andExpect(status().isForbidden)

        val student = entitled("0781000003")
        val session = registerAndStart(student, contestId)
        val qids = session.questions.map { it.id }
        val submitBody = objectMapper.writeValueAsString(
            mapOf(qids[0] to "3", qids[1] to "6", qids[2] to "2")
        )
        val resultBody = mockMvc.perform(
            post("/contests/${contestId}/submit").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(resultBody, ContestResultPayload::class.java)
        check(result.score == 4)
        check(result.totalPoints == 6)
        check(result.percentage == 67)
        check(result.correctAnswers == 2)
        check(result.integrityScore in 0..100)
        check(result.integrityFlags is List<String>)

        mockMvc.perform(
            post("/contests/${contestId}/submit").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(submitBody)
        ).andExpect(status().isConflict)
    }

    @Test
    fun `leaderboard ranks by server score and includes the user entry`() {
        val winner = entitled("0781000004")
        val loser = entitled("0781000005")
        val contestId = seedOngoing("Ranked Cup")

        val winnerSession = registerAndStart(winner, contestId)
        val loserSession = registerAndStart(loser, contestId)
        val wIds = winnerSession.questions.map { it.id }
        val lIds = loserSession.questions.map { it.id }

        val winnerSubmit = objectMapper.writeValueAsString(
            mapOf(wIds[0] to "4", wIds[1] to "6", wIds[2] to "2")
        )
        val loserSubmit = objectMapper.writeValueAsString(
            mapOf(lIds[0] to "3", lIds[1] to "5", lIds[2] to "3")
        )
        mockMvc.perform(
            post("/contests/${contestId}/submit").header("Authorization", auth(winner.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(winnerSubmit)
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/contests/${contestId}/submit").header("Authorization", auth(loser.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(loserSubmit)
        ).andExpect(status().isOk)

        val board = mockMvc.perform(
            get("/contests/${contestId}/leaderboard").header("Authorization", auth(loser.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(board, LeaderboardPayload::class.java)
        check(payload.entries.size == 2)
        check(payload.entries[0].score == 6)
        check(payload.entries[0].rank == 1)
        check(payload.entries[1].rank == 2)
        check(payload.userEntry?.rank == 2)
        check(payload.userEntry?.score == 0)
    }

    private companion object {
        val nextAdmin = AtomicInteger(0)
    }
}
