package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolConfigPayload
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * D16/A23: school admins CRUD the report logo and stamp through the school config, and no
 * school can read or change another school's branding.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SchoolConfigWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val configService: SchoolConfigService,
) {

    private lateinit var school: SchoolEntity
    private lateinit var otherSchool: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var otherAdmin: UserEntity

    private fun user(name: String, phone: String, schoolId: java.util.UUID): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@config.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = Role.TEACHER
        entity.subRole = SubRole.ICT_ADMIN
        entity.schoolId = schoolId
        entity.isActive = true
        entity.isVerified = true
        return userRepository.save(entity)
    }

    private fun token(entity: UserEntity): String {
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("identifier" to entity.email, "password" to "password123"))),
        ).andReturn().response
        check(response.status == 200) { "login failed: " + response.status }
        return objectMapper.readValue(response.contentAsString, AuthResponse::class.java).sessionToken!!
    }

    private fun body(logo: String?, stamp: String?, motto: String? = "Nurture - Discover - Excel"): String =
        objectMapper.writeValueAsString(
            SchoolConfigPayload(
                schoolId = school.id.toString(),
                schoolName = school.name,
                motto = motto,
                logoUrl = logo,
                stampUrl = stamp,
                watermarkText = "Milimani Primary School",
            ),
        )

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Milimani Primary School"; isActive = true })
        otherSchool = schoolRepository.save(SchoolEntity().apply { name = "Moi Avenue School"; isActive = true })
        admin = user("School ICT Admin", "0766600001", school.id)
        otherAdmin = user("Other ICT Admin", "0766600002", otherSchool.id)
    }

    @Test
    fun `school admin sets replaces and removes the logo and stamp`() {
        val t = token(admin)
        val created = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("https://cdn.example/logo.png", "https://cdn.example/stamp.png")),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        assertEquals("https://cdn.example/logo.png", created.logoUrl)
        assertEquals("https://cdn.example/stamp.png", created.stampUrl)

        // The report renderer reads the same service, so both images flow into every report.
        val branding = configService.branding(school.id)
        assertEquals("https://cdn.example/logo.png", branding.logoUrl)
        assertEquals("https://cdn.example/stamp.png", branding.stampUrl)

        // Replace both with new URLs.
        val replaced = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("https://cdn.example/logo-2.png", "https://cdn.example/stamp-2.png")),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        assertEquals("https://cdn.example/logo-2.png", replaced.logoUrl)
        assertEquals("https://cdn.example/stamp-2.png", replaced.stampUrl)

        // Remove both: null resets each report to the BrainBox mark / Exam Office stamp.
        val removed = objectMapper.readValue(
            mockMvc.perform(
                put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(null, null)),
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolConfigPayload::class.java,
        )
        assertNull(removed.logoUrl)
        assertNull(removed.stampUrl)
        assertNull(configService.branding(school.id).stampUrl)
    }

    @Test
    fun `branding image URLs are validated`() {
        val t = token(admin)
        listOf("javascript:alert(1)", "not-a-url", "/relative/logo.png").forEach { bad ->
            mockMvc.perform(
                put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + t)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(null, bad)),
            ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `another school cannot read or change the branding`() {
        val otherToken = token(otherAdmin)
        mockMvc.perform(get("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/config").header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(null, "https://cdn.example/other.png")),
        ).andExpect(status().isForbidden)
        assertNull(configService.branding(school.id).stampUrl)
    }
}
