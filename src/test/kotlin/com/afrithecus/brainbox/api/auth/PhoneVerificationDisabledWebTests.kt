package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.PhoneVerificationStatusPayload
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

/** The default-off flag (docs/phone_verification.md): status explains, writes refuse. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PhoneVerificationDisabledWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Disabled School"; isActive = true })
        val user = UserEntity()
        user.phoneNumber = "0755600203"
        user.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        user.name = "Disabled Flow"
        user.role = Role.STUDENT
        user.schoolId = school.id
        user.isActive = true
        user.isVerified = true
        userRepository.save(user)
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0755600203\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        token = objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    @Test
    fun `status explains the feature is off and writes are refused`() {
        val body = mockMvc.perform(get("/auth/phone/verification").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(body, PhoneVerificationStatusPayload::class.java)
        check(!payload.enabled)
        check(!payload.canRequest)
        check(!payload.verified)
        mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable)
        mockMvc.perform(
            post("/auth/phone/verification/confirm").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}")
        ).andExpect(status().isServiceUnavailable)
    }
}
