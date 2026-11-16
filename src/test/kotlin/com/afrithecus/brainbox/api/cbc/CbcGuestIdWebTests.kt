package com.afrithecus.brainbox.api.cbc

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectEntity
import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectRepository
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Findings "# cbc projects": a guest-id problem must never surface a header/schema hint to a
 * visitor. Public reads tolerate a missing or malformed id (anonymous browse has no vote
 * state); anonymous mutations answer one generic 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CbcGuestIdWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val userRepository: com.afrithecus.brainbox.api.identity.repository.UserRepository,
    @Autowired private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
    @Autowired private val projectRepository: CbcProjectRepository,
) {
    private lateinit var projectId: String

    @BeforeEach
    fun setUp() {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        // cbc_projects.student_id is an FK to users: the credited learner must be a real row.
        val learner = userRepository.save(com.afrithecus.brainbox.api.identity.entity.UserEntity().apply {
            name = "A Learner"
            phoneNumber = "0766600099"
            email = "guestid.learner@cbc.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = com.afrithecus.brainbox.api.identity.model.Role.STUDENT
            schoolId = school.id
            isActive = true
            isVerified = true
        })
        val project = projectRepository.save(CbcProjectEntity().apply {
            studentId = learner.id
            studentName = "A Learner"
            schoolId = school.id
            schoolName = school.name
            title = "My science project"
            description = "A working model"
            subject = "Science"
            cbcStrand = "SCI"
            cbcSubStrand = "SCI.1"
            gradeBand = "UPPER_PRIMARY"
            status = ProjectStatus.APPROVED
        })
        projectId = project.id.toString()
    }

    @Test
    fun `public reads work without a guest id and tolerate a malformed one`() {
        mockMvc.perform(get("/cbc/public/projects")).andExpect(status().isOk)
        mockMvc.perform(get("/cbc/public/projects").header("X-Guest-Id", "web_1794_abc"))
            .andExpect(status().isOk)
        mockMvc.perform(get("/cbc/public/projects/" + projectId).header("X-Guest-Id", "not-a-guest-id"))
            .andExpect(status().isOk)
        mockMvc.perform(get("/cbc/public/projects/featured").header("X-Guest-Id", "web_123"))
            .andExpect(status().isOk)
    }

    @Test
    fun `an anonymous mutation with a bad guest id answers one generic bad request`() {
        val vote = "{\"voteType\":\"UPVOTE\"}"
        val missing = mockMvc.perform(
            post("/cbc/public/projects/" + projectId + "/vote")
                .contentType(MediaType.APPLICATION_JSON).content(vote),
        ).andExpect(status().isBadRequest).andReturn().response.contentAsString
        check(missing.contains("Request could not be verified")) { "body=" + missing }
        check(!missing.contains("X-Guest-Id", ignoreCase = true)) { "leaked the header name: " + missing }
        check(!missing.contains("guest_")) { "leaked the schema: " + missing }

        val malformed = mockMvc.perform(
            post("/cbc/public/projects/" + projectId + "/vote").header("X-Guest-Id", "web_1_2")
                .contentType(MediaType.APPLICATION_JSON).content(vote),
        ).andExpect(status().isBadRequest).andReturn().response.contentAsString
        check(malformed.contains("Request could not be verified")) { "body=" + malformed }
        check(!malformed.contains("guest_")) { "leaked the schema: " + malformed }

        // A well-formed anonymous id still votes normally.
        val valid = "guest_" + UUID.randomUUID()
        mockMvc.perform(
            post("/cbc/public/projects/" + projectId + "/vote").header("X-Guest-Id", valid)
                .contentType(MediaType.APPLICATION_JSON).content(vote),
        ).andExpect(status().isOk)
        mockMvc.perform(
            delete("/cbc/public/projects/" + projectId + "/vote").header("X-Guest-Id", valid),
        ).andExpect(status().isOk)
    }
}
