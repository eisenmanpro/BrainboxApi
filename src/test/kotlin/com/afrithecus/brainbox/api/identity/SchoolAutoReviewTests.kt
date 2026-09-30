package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.repository.SchoolRegistrationRequestRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * product_ops_roadmap item 5: a school created by signup becomes publicly visible at once, so it
 * must also be visible to the console for review. Otherwise a public school exists that nobody
 * approved, which is exactly what "the console is the origin of school data" forbids.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolAutoReviewTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val requests: SchoolRegistrationRequestRepository,
) {

    private fun signup(phone: String, schoolName: String): AuthResponse {
        val body = "{\"name\":\"Learner " + phone + "\",\"phoneNumber\":\"" + phone +
            "\",\"password\":\"password123\",\"role\":\"STUDENT\",\"schoolName\":\"" + schoolName + "\"}"
        val raw = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(raw, AuthResponse::class.java)
    }

    @Test
    fun `a school minted at signup lands in the review queue`() {
        val name = "Auto Review Academy"
        check(requests.findBySchoolNameIgnoreCaseAndStatus(name, "PENDING") == null) { "test name must start clean" }

        signup("0778200001", name)

        val queued = requests.findBySchoolNameIgnoreCaseAndStatus(name, "PENDING")
        check(queued != null) { "a signup-created school must be reviewable by the console" }
        check(queued!!.submittedBy == null) { "signup is not an authenticated submission" }
    }

    @Test
    fun `the same name is reported as awaiting review, not as a duplicate`() {
        val name = "Auto Review Secondary"
        val auth = signup("0778200002", name)

        val raw = mockMvc.perform(
            post("/auth/register-school")
                .header("Authorization", "Bearer " + auth.sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"rq-" + name.hashCode() + "\",\"schoolName\":\"" + name + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val result = objectMapper.readValue(raw, AuthResponse::class.java)
        check(result.success) { "expected success, got: " + result.message }
        check(result.message.contains("awaiting review")) { "message was: " + result.message }
    }
}
