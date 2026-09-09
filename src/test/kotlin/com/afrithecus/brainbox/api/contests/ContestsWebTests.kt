package com.afrithecus.brainbox.api.contests

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.contests.web.ContestPayload
import com.afrithecus.brainbox.api.contests.web.CreateContestQuestionRequest
import com.afrithecus.brainbox.api.contests.web.CreateContestRequest
import com.afrithecus.brainbox.api.contests.web.RegistrationResponse
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contest contract tests (doc 05 §1.2-§1.4): window-based lists, detail state,
 * registration entitlement/capacity/window rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContestsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signup(phone: String, role: String = "STUDENT"): AuthResponse {
        val body = """{"name":"User ${phone}","phoneNumber":"${phone}","password":"password123","role":"${role}"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun adminToken(): String {
        val n = nextAdmin.incrementAndGet()
        val phone = "0799" + (300 + n)
        val email = "admin" + n + "@contests.test"
        val admin = UserEntity().apply {
            phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Contest Admin"
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

    /** Verified student with an active EXPLORER subscription. */
    private fun entitledStudent(phone: String): AuthResponse {
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

    private fun createContest(
        admin: String,
        title: String,
        startMs: Long,
        endMs: Long,
        maxParticipants: Int? = null,
    ): String {
        val request = CreateContestRequest(
            title = title, subject = "Mathematics", grade = "Form 3",
            startTime = startMs, endTime = endMs, prize = "Ksh 500",
            maxParticipants = maxParticipants,
            questions = listOf(
                CreateContestQuestionRequest(
                    text = "2+2?", type = "MCQ", options = listOf("3", "4", "5"),
                    correctAnswer = "4", points = 2,
                )
            ),
        )
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/admin/contests").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ContestPayload::class.java).id
    }

    private fun contestList(student: AuthResponse, path: String): List<ContestPayload> {
        val body = mockMvc.perform(
            get(path).header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<ContestPayload>::class.java).toList()
    }

    @Test
    fun `window lists split contests correctly`() {
        val admin = adminToken()
        val student = entitledStudent("0780000001")
        val now = Instant.now()
        createContest(admin, "Upcoming Cup", now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli())
        createContest(admin, "Live Cup", now.minusSeconds(3600).toEpochMilli(), now.plusSeconds(3600).toEpochMilli())
        createContest(admin, "Finished Cup", now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(3600).toEpochMilli())

        val upcoming = contestList(student, "/contests/upcoming")
        val ongoing = contestList(student, "/contests/ongoing")
        val completed = contestList(student, "/contests/completed")
        check(upcoming.single().title == "Upcoming Cup")
        check(ongoing.single().title == "Live Cup")
        check(completed.single().title == "Finished Cup")
        check(upcoming.single().isUserRegistered == false)
    }

    @Test
    fun `entitled students register once and duplicate registration conflicts`() {
        val admin = adminToken()
        val student = entitledStudent("0780000002")
        val now = Instant.now()
        val contestId = createContest(admin, "Open Cup", now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli())

        val regBody = mockMvc.perform(
            post("/contests/${contestId}/register").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(regBody, RegistrationResponse::class.java).success)

        val detail = mockMvc.perform(
            get("/contests/${contestId}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val contest = objectMapper.readValue(detail, ContestPayload::class.java)
        check(contest.isUserRegistered)
        check(contest.registeredCount == 1)
        check(contest.questions == null) // questions not revealed before the start

        mockMvc.perform(
            post("/contests/${contestId}/register").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `base tier and unverified students are refused and started contests close`() {
        val admin = adminToken()
        val freeStudent = signup("0780000003") // BASE, unverified
        val now = Instant.now()

        val upcomingId = createContest(admin, "Paid Cup", now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli())
        mockMvc.perform(
            post("/contests/${upcomingId}/register").header("Authorization", auth(freeStudent.sessionToken!!))
        ).andExpect(status().isForbidden)

        val liveId = createContest(admin, "Live2", now.minusSeconds(3600).toEpochMilli(), now.plusSeconds(3600).toEpochMilli())
        val entitled = entitledStudent("0780000004")
        mockMvc.perform(
            post("/contests/${liveId}/register").header("Authorization", auth(entitled.sessionToken!!))
        ).andExpect(status().isConflict) // registration closed once started
    }

    @Test
    fun `capacity limits registrations and parents cannot join`() {
        val admin = adminToken()
        val now = Instant.now()
        val contestId = createContest(
            admin, "Capacity Cup", now.plusSeconds(3600).toEpochMilli(),
            now.plusSeconds(7200).toEpochMilli(), maxParticipants = 1,
        )

        val first = entitledStudent("0780000005")
        mockMvc.perform(
            post("/contests/${contestId}/register").header("Authorization", auth(first.sessionToken!!))
        ).andExpect(status().isOk)

        val second = entitledStudent("0780000006")
        mockMvc.perform(
            post("/contests/${contestId}/register").header("Authorization", auth(second.sessionToken!!))
        ).andExpect(status().isConflict)

        val parent = signup("0780000007", role = "PARENT")
        mockMvc.perform(
            post("/contests/${contestId}/register").header("Authorization", auth(parent.sessionToken!!))
        ).andExpect(status().isForbidden)
    }

    private companion object {
        val nextAdmin = AtomicInteger(0)
    }
}
