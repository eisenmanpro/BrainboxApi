package com.afrithecus.brainbox.api.doubt

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.doubt.web.AskQuestionRequest
import com.afrithecus.brainbox.api.doubt.web.DoubtAnswerPayload
import com.afrithecus.brainbox.api.doubt.web.DoubtQuestionPayload
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
 * Doubt solving forum tests (doc 05 §3): ask/list/detail, answers, accept gating,
 * voting without double counting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DoubtWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signupStudent(phone: String): AuthResponse {
        val body = """{"name":"Student ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun newTeacher(phone: String): AuthResponse {
        val email = phone + "@doubt.test"
        val teacher = UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("teacherpass123") ?: error("encode")
            name = "Doubt Teacher"
            role = Role.TEACHER
            isActive = true
            isVerified = true
        }
        userRepository.save(teacher)
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"teacherpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java)
    }

    private fun auth(token: String) = "Bearer " + token

    private fun ask(student: AuthResponse, title: String, subject: String = "MATHEMATICS"): DoubtQuestionPayload {
        val request = AskQuestionRequest(title = title, body = "Could someone explain this?", subject = subject)
        val body = objectMapper.writeValueAsString(request)
        val response = mockMvc.perform(
            post("/doubt/questions").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, DoubtQuestionPayload::class.java)
    }

    @Test
    fun `ask list filter detail answers and status transitions`() {
        val student = signupStudent("0778000001")
        val teacher = newTeacher("0778999001")

        val q = ask(student, "How do I factorise quadratics?", subject = "MATHEMATICS")
        check(q.status == "OPEN")
        check(q.authorName == student.user.name)

        // subject filter works
        val math = mockMvc.perform(
            get("/doubt/questions?subject=MATHEMATICS").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(math, Array<DoubtQuestionPayload>::class.java).any { it.id == q.id })

        val physics = mockMvc.perform(
            get("/doubt/questions?subject=PHYSICS").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(physics, Array<DoubtQuestionPayload>::class.java).none { it.id == q.id })

        // unanswered sort includes it before answers arrive
        val unanswered = mockMvc.perform(
            get("/doubt/questions?sort=unanswered").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(unanswered, Array<DoubtQuestionPayload>::class.java).any { it.id == q.id })

        // teacher answers -> question becomes ANSWERED, role is TEACHER
        val answerResponse = mockMvc.perform(
            post("/doubt/questions/${q.id}/answers").header("Authorization", auth(teacher.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"Use the product-sum method."}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val answer = objectMapper.readValue(answerResponse, DoubtAnswerPayload::class.java)
        check(answer.authorRole == "TEACHER")
        check(!answer.isAccepted)

        val detail = mockMvc.perform(
            get("/doubt/questions/${q.id}").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val refreshed = objectMapper.readValue(detail, DoubtQuestionPayload::class.java)
        check(refreshed.status == "ANSWERED")

        val answersList = mockMvc.perform(
            get("/doubt/questions/${q.id}/answers").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(answersList, Array<DoubtAnswerPayload>::class.java).single().id == answer.id)
    }

    @Test
    fun `only the author accepts and voting never double counts`() {
        val author = signupStudent("0778000002")
        val other = signupStudent("0778000003")
        val q = ask(author, "Why is the sky blue?")

        // non-author cannot accept; missing answer -> 404
        mockMvc.perform(
            post("/doubt/answers/${UUID.randomUUID()}/accept").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isNotFound)

        val answerBody = mockMvc.perform(
            post("/doubt/questions/${q.id}/answers").header("Authorization", auth(other.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"Rayleigh scattering."}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val answer = objectMapper.readValue(answerBody, DoubtAnswerPayload::class.java)

        // only author may accept
        mockMvc.perform(
            post("/doubt/answers/${answer.id}/accept").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/doubt/answers/${answer.id}/accept").header("Authorization", auth(author.sessionToken!!))
        ).andExpect(status().isNoContent)

        val afterAccept = mockMvc.perform(
            get("/doubt/questions/${q.id}").header("Authorization", auth(author.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(afterAccept, DoubtQuestionPayload::class.java).status == "CLOSED")

        // voting: up then up again only counts once
        mockMvc.perform(
            post("/doubt/questions/${q.id}/vote?voteType=up").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/doubt/questions/${q.id}/vote?voteType=up").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isNoContent)
        val voted = mockMvc.perform(
            get("/doubt/questions/${q.id}").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(voted, DoubtQuestionPayload::class.java).voteCount == 1)

        // flip to down adjusts by -2 relative to the up
        mockMvc.perform(
            post("/doubt/answers/${answer.id}/vote?voteType=down").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isNoContent)
        val answersAfter = mockMvc.perform(
            get("/doubt/questions/${q.id}/answers").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(answersAfter, Array<DoubtAnswerPayload>::class.java).single().voteCount == 0)
    }

    @Test
    fun `search finds matching questions`() {
        val student = signupStudent("0778000004")
        ask(student, "Derivative of x squared", subject = "MATHEMATICS")
        ask(student, "Photosynthesis reactants", subject = "BIOLOGY")

        val found = mockMvc.perform(
            get("/doubt/questions?search=photosynthesis").header("Authorization", auth(student.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val list = objectMapper.readValue(found, Array<DoubtQuestionPayload>::class.java)
        check(list.size == 1)
        check(list.single().subject == "BIOLOGY")
    }
}
