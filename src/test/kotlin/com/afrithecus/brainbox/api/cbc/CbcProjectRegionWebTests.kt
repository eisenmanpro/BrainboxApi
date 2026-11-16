package com.afrithecus.brainbox.api.cbc

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import com.afrithecus.brainbox.api.cbc.repository.CbcProjectRepository
import com.afrithecus.brainbox.api.cbc.web.CbcProjectPayload
import com.afrithecus.brainbox.api.cbc.web.ProjectListResponsePayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * PRJ-05: projects carry country/county, the list filters on them, and a submission
 * without a region falls back to the school's county instead of inventing one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CbcProjectRegionWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val projectRepository: CbcProjectRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private lateinit var school: SchoolEntity
    private lateinit var student: UserEntity

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
            name = "Region Test School"
            county = "Kisumu"
            isActive = true
        })
        student = userRepository.save(UserEntity().apply {
            name = "Region Learner"
            phoneNumber = "0755800001"
            email = "region@student.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            role = Role.STUDENT
            schoolId = school.id
            gradeLevel = "Grade 6"
            isActive = true
            isVerified = true
        })
    }

    @Test
    fun `a submission defaults its county from the school and is filterable by country`() {
        val body = objectMapper.writeValueAsString(mapOf(
            "title" to "Water project",
            "description" to "A project about clean water in the community",
            "subject" to "Science",
            "gradeBand" to "JUNIOR",
            "mediaUrls" to listOf("https://cdn.test/water.jpg"),
        ))
        val created = objectMapper.readValue(
            mockMvc.perform(
                post("/cbc/projects").header("Authorization", "Bearer " + token(student))
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            CbcProjectPayload::class.java,
        )
        check(created.country == "Kenya")
        check(created.county == "Kisumu")

        // Approve it directly, then prove the public filter finds it by country.
        val entity = projectRepository.findById(UUID.fromString(created.id)).orElseThrow()
        entity.status = ProjectStatus.APPROVED
        projectRepository.save(entity)

        val publicList = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects?country=Kenya"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(publicList.projects.any { it.id == created.id })

        val wrongCountry = objectMapper.readValue(
            mockMvc.perform(get("/cbc/public/projects?country=Uganda"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            ProjectListResponsePayload::class.java,
        )
        check(wrongCountry.projects.none { it.id == created.id })
    }
}
