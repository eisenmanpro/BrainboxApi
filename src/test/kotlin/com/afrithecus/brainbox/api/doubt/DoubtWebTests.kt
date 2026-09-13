package com.afrithecus.brainbox.api.doubt

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.doubt.web.AnswerRequest
import com.afrithecus.brainbox.api.doubt.web.AskQuestionRequest
import com.afrithecus.brainbox.api.doubt.web.DoubtAnswerPayload
import com.afrithecus.brainbox.api.doubt.web.DoubtQuestionPayload
import com.afrithecus.brainbox.api.doubt.web.VoteResultPayload
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
 * Doubt solving forum tests (doc 05 §3): ask/list/detail, answers, bookmarks,
 * accept gating, and voting tallies/directions without double counting.
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

    private fun answer(token: String, questionId: String, body: String): DoubtAnswerPayload {
        val payload = objectMapper.writeValueAsString(AnswerRequest(body))
        val response = mockMvc.perform(
            post("/doubt/questions/$questionId/answers").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(payload)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, DoubtAnswerPayload::class.java)
    }

    private fun accept(token: String, answerId: String): DoubtQuestionPayload {
        val response = mockMvc.perform(
            post("/doubt/answers/$answerId/accept").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, DoubtQuestionPayload::class.java)
    }

    private fun bookmark(token: String, questionId: String): DoubtQuestionPayload {
        val response = mockMvc.perform(
            post("/doubt/questions/$questionId/bookmark").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, DoubtQuestionPayload::class.java)
    }

    private fun voteQuestion(token: String, questionId: String, voteType: String): VoteResultPayload {
        val response = mockMvc.perform(
            post("/doubt/questions/$questionId/vote?voteType=$voteType").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, VoteResultPayload::class.java)
    }

    private fun voteAnswer(token: String, answerId: String, voteType: String): VoteResultPayload {
        val response = mockMvc.perform(
            post("/doubt/answers/$answerId/vote?voteType=$voteType").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, VoteResultPayload::class.java)
    }

    private fun detail(token: String, questionId: String): DoubtQuestionPayload {
        val response = mockMvc.perform(
            get("/doubt/questions/$questionId").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, DoubtQuestionPayload::class.java)
    }

    private fun answers(token: String, questionId: String): List<DoubtAnswerPayload> {
        val response = mockMvc.perform(
            get("/doubt/questions/$questionId/answers").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, Array<DoubtAnswerPayload>::class.java).toList()
    }

    @Test
    fun `ask list filter detail answers and status transitions`() {
        val student = signupStudent("0778000001")
        val teacher = newTeacher("0778999001")

        val q = ask(student, "How do I factorise quadratics?", subject = "MATHEMATICS")
        check(q.status == "OPEN")
        check(q.authorName == student.user.name)
        check(q.upvotes == 0)
        check(q.downvotes == 0)
        check(q.answerCount == 0)
        check(q.currentUserVote == 0)
        check(!q.isBookmarked)
        check(!q.isAcceptedAnswer)
        check(q.acceptedAnswerId == null)

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
        val answer = answer(teacher.sessionToken!!, q.id, "Use the product-sum method.")
        check(answer.authorRole == "TEACHER")
        check(!answer.isAccepted)
        check(answer.upvotes == 0)
        check(answer.downvotes == 0)
        check(answer.currentUserVote == 0)

        val refreshed = detail(student.sessionToken!!, q.id)
        check(refreshed.status == "ANSWERED")
        check(refreshed.answerCount == 1)

        val listed = answers(student.sessionToken!!, q.id)
        check(listed.single().id == answer.id)
    }

    @Test
    fun `bookmarks toggle per caller and only for the caller`() {
        val owner = signupStudent("0778000002")
        val other = signupStudent("0778000003")
        val q = ask(owner, "Why is the sky blue?")

        val first = bookmark(owner.sessionToken!!, q.id)
        check(first.isBookmarked)
        check(detail(owner.sessionToken!!, q.id).isBookmarked)
        check(!detail(other.sessionToken!!, q.id).isBookmarked)

        // second tap toggles it back off
        val second = bookmark(owner.sessionToken!!, q.id)
        check(!second.isBookmarked)
        check(!detail(owner.sessionToken!!, q.id).isBookmarked)

        // another caller's bookmark is independent
        check(bookmark(other.sessionToken!!, q.id).isBookmarked)
        check(detail(other.sessionToken!!, q.id).isBookmarked)
        check(!detail(owner.sessionToken!!, q.id).isBookmarked)
    }

    @Test
    fun `voting reports split tallies and caller direction`() {
        val author = signupStudent("0778000004")
        val voter = signupStudent("0778000005")
        val q = ask(author, "What is entropy?")

        val up = voteQuestion(voter.sessionToken!!, q.id, "up")
        check(up.success)
        check(up.newUpvotes == 1)
        check(up.newDownvotes == 0)
        check(up.message.isNotBlank())

        val afterUp = detail(voter.sessionToken!!, q.id)
        check(afterUp.upvotes == 1)
        check(afterUp.downvotes == 0)
        check(afterUp.currentUserVote == 1)
        // the author sees the tally but no personal vote
        check(detail(author.sessionToken!!, q.id).currentUserVote == 0)

        // the same direction again removes the vote
        val removed = voteQuestion(voter.sessionToken!!, q.id, "up")
        check(removed.success)
        check(removed.newUpvotes == 0)
        check(removed.newDownvotes == 0)

        // down vote
        val down = voteQuestion(voter.sessionToken!!, q.id, "down")
        check(down.newUpvotes == 0)
        check(down.newDownvotes == 1)
        check(detail(voter.sessionToken!!, q.id).currentUserVote == -1)

        // opposite direction switches
        val switched = voteQuestion(voter.sessionToken!!, q.id, "up")
        check(switched.newUpvotes == 1)
        check(switched.newDownvotes == 0)
        check(detail(voter.sessionToken!!, q.id).currentUserVote == 1)

        // answer votes carry the same shape
        val answer = answer(author.sessionToken!!, q.id, "It measures disorder.")
        val answerDown = voteAnswer(voter.sessionToken!!, answer.id, "down")
        check(answerDown.success)
        check(answerDown.newUpvotes == 0)
        check(answerDown.newDownvotes == 1)
        val answerPayload = answers(voter.sessionToken!!, q.id).single()
        check(answerPayload.upvotes == 0)
        check(answerPayload.downvotes == 1)
        check(answerPayload.currentUserVote == -1)
    }

    @Test
    fun `only the author accepts and the accepted answer is reported`() {
        val author = signupStudent("0778000006")
        val other = signupStudent("0778000007")
        val q = ask(author, "Define velocity.")

        // missing answer -> 404
        mockMvc.perform(
            post("/doubt/answers/${UUID.randomUUID()}/accept").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isNotFound)

        val answer = answer(other.sessionToken!!, q.id, "Displacement over time.")

        // only author may accept
        mockMvc.perform(
            post("/doubt/answers/${answer.id}/accept").header("Authorization", auth(other.sessionToken!!))
        ).andExpect(status().isForbidden)

        val accepted = accept(author.sessionToken!!, answer.id)
        check(accepted.status == "CLOSED")
        check(accepted.isAcceptedAnswer)
        check(accepted.acceptedAnswerId == answer.id)
        check(accepted.answerCount == 1)

        check(answers(other.sessionToken!!, q.id).single().isAccepted)

        val refreshed = detail(author.sessionToken!!, q.id)
        check(refreshed.isAcceptedAnswer)
        check(refreshed.acceptedAnswerId == answer.id)
    }

    @Test
    fun `search finds matching questions`() {
        val student = signupStudent("0778000008")
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
