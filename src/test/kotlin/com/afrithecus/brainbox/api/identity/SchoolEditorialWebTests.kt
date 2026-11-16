package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.AuditLogRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolEditorialPayload
import com.afrithecus.brainbox.api.identity.web.SchoolListPayload
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * FEAT-01/FEAT-06: the public school-list payload carries editorial fields, and only a
 * platform moderator can set them. A school's own ICT admin is refused and cannot
 * promote its own listing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolEditorialWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val auditLogRepository: AuditLogRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity

    private fun account(role: Role, phone: String, subRole: SubRole? = null, schoolId: UUID? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            this.name = "Editorial " + phone
            this.phoneNumber = phone
            email = phone + "@editorial.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.role = role
            this.subRole = subRole
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        })

    private fun token(user: UserEntity): String {
        val body = "{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}"
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java).sessionToken!!
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply {
            name = "Editorial Test School"
            isActive = true
            county = "Nairobi"
            logoUrls = """["https://cdn.test/logo.png"]"""
        })
    }

    @Test
    fun `the public list carries editorial fields and only a platform moderator may write them`() {
        val moderator = account(Role.ADMIN, "0755600001")
        val ictAdmin = account(Role.TEACHER, "0755600002", SubRole.ICT_ADMIN, school.id)
        val moderatorToken = token(moderator)
        val ictToken = token(ictAdmin)

        val body = objectMapper.writeValueAsString(mapOf(
            "imageUrls" to listOf("https://cdn.test/one.jpg", "https://cdn.test/two.jpg"),
            "isFeatured" to true,
            "featuredTagline" to "A school that learns together",
            "story" to "Built by the community.",
        ))

        // A school admin cannot feature its own listing (FEAT-06).
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/editorial")
                .header("Authorization", "Bearer " + ictToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            put("/admin/schools/" + school.id + "/editorial")
                .header("Authorization", "Bearer " + moderatorToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val list = objectMapper.readValue(
            mockMvc.perform(get("/schools/all")).andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolListPayload>::class.java,
        )
        val card = list.first { it.id == school.id.toString() }
        check(card.isFeatured)
        check(card.imageUrls == listOf("https://cdn.test/one.jpg", "https://cdn.test/two.jpg"))
        check(card.featuredTagline == "A school that learns together")
        check(card.story == "Built by the community.")

        // The write is auditable at the platform level. A plain ADMIN has no console
        // capability, so read the platform audit rows directly rather than through the
        // capability-gated console endpoint.
        val audit = auditLogRepository.findAllBySchoolIdIsNullOrderByCreatedAtDesc(PageRequest.of(0, 50))
        check(audit.any { it.action.contains("Featured school listing") })

        val read = objectMapper.readValue(
            mockMvc.perform(
                get("/admin/schools/" + school.id + "/editorial")
                    .header("Authorization", "Bearer " + moderatorToken)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolEditorialPayload::class.java,
        )
        check(read.isFeatured)
    }

    @Test
    fun `an invalid image URL is refused instead of stored`() {
        val moderator = account(Role.ADMIN, "0755600003")
        val moderatorToken = token(moderator)
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/editorial")
                .header("Authorization", "Bearer " + moderatorToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"imageUrls":["javascript:alert(1)"],"isFeatured":false}""")
        ).andExpect(status().isBadRequest)
    }
}
