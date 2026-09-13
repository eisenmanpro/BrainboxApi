package com.afrithecus.brainbox.api.security

import com.afrithecus.brainbox.api.auth.web.AuthResponse
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

/** Logout must kill the access token immediately, not at its TTL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthRevocationWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    @Test
    fun `logout immediately invalidates the access token`() {
        val entity = UserEntity()
        entity.phoneNumber = "0700000900"
        entity.email = "revoke@auth.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = "Revoke User"
        entity.role = Role.STUDENT
        entity.isVerified = true
        entity.isActive = true
        userRepository.save(entity)

        val body = objectMapper.writeValueAsString(mapOf("identifier" to entity.email, "password" to "password123"))
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val token = objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk)
        mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized)
    }
}
