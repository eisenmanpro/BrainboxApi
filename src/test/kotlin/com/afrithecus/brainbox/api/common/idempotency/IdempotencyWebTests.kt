package com.afrithecus.brainbox.api.common.idempotency

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.common.error.ApiError
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
 * Idempotent POST (doc 11 §8.3/§8.4): same X-Idempotency-Key replays the cached
 * response verbatim instead of executing twice; errors are never cached.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdempotencyWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val repository: IdempotencyRepository,
) {

    private fun signup(phone: String) {
        mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Idem User","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}""")
        ).andExpect(status().isOk)
    }

    private fun login(phone: String, key: String): String =
        mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "dev").header("X-Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${phone}","password":"password123"}""")
        ).andReturn().response.contentAsString

    @Test
    fun `same key returns the identical cached response`() {
        signup("0731000001")
        val first = login("0731000001", "login-1")
        val second = login("0731000001", "login-1")

        check(first == second)
        check(repository.findByKeyHash(hashKey("login-1")) != null)
        val parsed = objectMapper.readValue(first, AuthResponse::class.java)
        check(parsed.sessionToken != null)
    }

    @Test
    fun `different keys are executed independently`() {
        signup("0731000002")
        val first = login("0731000002", "login-a")
        val second = login("0731000002", "login-b")
        val a = objectMapper.readValue(first, AuthResponse::class.java)
        val b = objectMapper.readValue(second, AuthResponse::class.java)
        require(a.sessionToken != b.sessionToken) {
            "tokens equal: A=" + a.sessionToken + " B=" + b.sessionToken
        }
    }

    @Test
    fun `errors are not cached so retries may recover`() {
        val firstBody = mockMvc.perform(
            post("/auth/login").header("X-Idempotency-Key", "bad-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"missing-user","password":"password123"}""")
        ).andExpect(status().isUnauthorized).andReturn().response.contentAsString
        val secondBody = mockMvc.perform(
            post("/auth/login").header("X-Idempotency-Key", "bad-login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"missing-user","password":"password123"}""")
        ).andExpect(status().isUnauthorized).andReturn().response.contentAsString

        check(objectMapper.readValue(firstBody, ApiError::class.java).error == "UNAUTHORIZED")
        check(objectMapper.readValue(secondBody, ApiError::class.java).error == "UNAUTHORIZED")
        check(repository.findByKeyHash(hashKey("bad-login")) == null)
    }

    private fun hashKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
