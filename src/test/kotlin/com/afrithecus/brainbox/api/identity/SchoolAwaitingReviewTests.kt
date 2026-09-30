package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRegistrationRequestRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolListPayload
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
 * product_ops_roadmap item 5, per the user's decision: a school that was **not** created from the
 * console must await review, so it may not appear in the public directory until approved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolAwaitingReviewTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val requests: SchoolRegistrationRequestRepository,
    @Autowired private val registration: SchoolRegistrationService,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signup(phone: String, schoolName: String) {
        val body = "{\"name\":\"Learner " + phone + "\",\"phoneNumber\":\"" + phone +
            "\",\"password\":\"password123\",\"role\":\"STUDENT\",\"schoolName\":\"" + schoolName + "\"}"
        mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
    }

    private fun publicList(): List<SchoolListPayload> = objectMapper.readValue(
        mockMvc.perform(get("/schools/all")).andExpect(status().isOk)
            .andReturn().response.contentAsString,
        Array<SchoolListPayload>::class.java,
    ).toList()

    @Test
    fun `a school from signup is hidden until the console approves it`() {
        val name = "Awaiting Review Academy"
        signup("0778300001", name)

        check(publicList().none { it.name.equals(name, ignoreCase = true) }) {
            "an unapproved school must not be publicly listed"
        }

        val queued = requests.findBySchoolNameIgnoreCaseAndStatus(name, "PENDING")
        check(queued != null) { "it must still be reviewable" }

        val admin = users.save(UserEntity().apply {
            phoneNumber = "0778300999"
            email = "review.admin@test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = "Review Admin"
            role = Role.ADMIN
            isActive = true
            isVerified = true
        })
        registration.approve(admin.id, queued!!.id.toString(), null)

        check(publicList().any { it.name.equals(name, ignoreCase = true) }) {
            "approval must make it public"
        }
    }
}
