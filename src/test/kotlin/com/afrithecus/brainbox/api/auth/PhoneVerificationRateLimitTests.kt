package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** Per-account hourly request cap (docs/phone_verification.md), with the resend cooldown disabled. */
@SpringBootTest(
    properties = [
        "app.auth.phone-verification.enabled=true",
        "app.auth.phone-verification.resend-cooldown=0s",
        "app.auth.phone-verification.max-requests-per-hour=3",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PhoneVerificationRateLimitTests.NotifierConfig::class)
class PhoneVerificationRateLimitTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var token: String

    @TestConfiguration
    class NotifierConfig {
        @Bean
        @Primary
        fun phoneVerificationNotifier(): PhoneVerificationNotifier = object : PhoneVerificationNotifier {
        override fun send(user: UserEntity, code: String) = Unit
    }
    }

    @BeforeEach
    fun setUp() {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Rate Limit School"; isActive = true })
        val user = UserEntity()
        user.phoneNumber = "0755600202"
        user.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        user.name = "Rate Limited"
        user.role = Role.STUDENT
        user.schoolId = school.id
        user.isActive = true
        user.isVerified = true
        userRepository.save(user)
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0755600202\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        token = objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    @Test
    fun `the hourly request cap refuses further codes`() {
        repeat(3) {
            mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk)
        }
        mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + token))
            .andExpect(status().isTooManyRequests)
    }
}
