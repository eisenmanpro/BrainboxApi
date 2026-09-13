package com.afrithecus.brainbox.api.push

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import com.afrithecus.brainbox.api.push.web.DeviceRegistrationRequest
import com.afrithecus.brainbox.api.push.web.DeviceUnregisterRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** FCM device registration (docs/ongoing/api_push_changes.md, LC-1). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PushControllerWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val tokenRepository: DeviceTokenRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun user(phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@push.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        name = "Push User"
        role = Role.STUDENT
        isVerified = true
        isActive = true
    })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + entity.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun register(token: String, body: DeviceRegistrationRequest) =
        mockMvc.perform(
            post("/push/device").header("Authorization", auth(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
        )

    @Test
    fun `register is idempotent per user and token`() {
        val user = user("0755700001")
        val t = token(user)
        repeat(2) {
            register(t, DeviceRegistrationRequest(token = "fcm-token-1", platform = "ANDROID", appVersion = "1.0"))
                .andExpect(status().isOk)
        }
        val rows = tokenRepository.findAllByUserId(user.id)
        check(rows.size == 1 && rows.single().token == "fcm-token-1")
        check(rows.single().appVersion == "1.0")
    }

    @Test
    fun `a shared token moves to the latest account and unregister is repeat safe`() {
        val first = user("0755700011")
        val second = user("0755700012")
        register(token(first), DeviceRegistrationRequest(token = "shared-token")).andExpect(status().isOk)
        register(token(second), DeviceRegistrationRequest(token = "shared-token")).andExpect(status().isOk)
        val row = requireNotNull(tokenRepository.findByToken("shared-token"))
        check(row.userId == second.id) { "the latest registration wins" }

        val body = DeviceUnregisterRequest("shared-token")
        repeat(2) {
            mockMvc.perform(
                post("/push/device/unregister").header("Authorization", auth(token(second)))
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
            ).andExpect(status().isOk)
        }
        check(tokenRepository.findByToken("shared-token") == null)
    }

    @Test
    fun `unregister only removes the caller's own token`() {
        val owner = user("0755700021")
        val other = user("0755700022")
        register(token(owner), DeviceRegistrationRequest(token = "owned-token")).andExpect(status().isOk)
        mockMvc.perform(
            post("/push/device/unregister").header("Authorization", auth(token(other)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(DeviceUnregisterRequest("owned-token")))
        ).andExpect(status().isOk)
        check(tokenRepository.findByToken("owned-token") != null) { "another account must not release it" }
    }

    @Test
    fun `validation and authentication`() {
        val user = user("0755700031")
        val t = token(user)
        register(t, DeviceRegistrationRequest(token = "  ")).andExpect(status().isBadRequest)
        register(t, DeviceRegistrationRequest(token = "tok", platform = "CARRIER_PIGEON")).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/push/device").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(DeviceRegistrationRequest(token = "tok")))
        ).andExpect(status().isUnauthorized)
    }
}
