package com.afrithecus.brainbox.api.common.error

import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.security.JwtTokenService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.boot.test.context.TestConfiguration
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Exercises the standard error envelope (doc 11 §8.1/§8.2) end to end through
 * the real filter chain and controller advice. Probe endpoints are protected,
 * so requests authenticate with a real JWT issued by [JwtTokenService].
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorEnvelopeWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jwtTokenService: JwtTokenService,
) {

    private val bearerToken: String by lazy {
        jwtTokenService.issueAccessToken(userId = UUID.randomUUID(), role = Role.ADMIN)
    }

    private fun bearer() = "Bearer " + bearerToken

    @Test
    fun `domain ApiException maps to its code and status`() {
        val body = mockMvc.perform(get("/probe/api-error").header("Authorization", bearer()))
            .andExpect(status().isConflict)
            .andReturn().response.contentAsString

        val json = objectMapper.readTree(body)
        check(json["error"].asString() == "CONFLICT")
        check(json["message"].asString() == "conflict boom")
        check(json["details"]["key"].asString() == "value")
        check(json["timestamp"].isNumber)
        check(json["path"].asString() == "/probe/api-error")
    }

    @Test
    fun `validation failures carry field details`() {
        val body = mockMvc.perform(
            post("/probe/validate")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":""}""")
        ).andExpect(status().isBadRequest)
            .andReturn().response.contentAsString

        val json = objectMapper.readTree(body)
        check(json["error"].asString() == "INVALID_ARGUMENT")
        check(json["details"].has("name"))
    }

    @Test
    fun `malformed body maps to invalid argument`() {
        mockMvc.perform(
            post("/probe/validate")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `unknown resource returns 404 envelope`() {
        val body = mockMvc.perform(get("/probe/does-not-exist").header("Authorization", bearer()))
            .andExpect(status().isNotFound)
            .andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        check(json["error"].asString() == "NOT_FOUND")
    }

    @Test
    fun `unexpected exceptions never leak internals`() {
        val body = mockMvc.perform(get("/probe/boom").header("Authorization", bearer()))
            .andExpect(status().isInternalServerError)
            .andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        check(json["error"].asString() == "INTERNAL_ERROR")
        check(json["message"].asString() == "Something went wrong")
        check(!body.contains("secret detail"))
    }

    @Test
    fun `protected endpoint without token returns 401 envelope`() {
        val body = mockMvc.perform(get("/probe/authenticated"))
            .andExpect(status().isUnauthorized)
            .andReturn().response.contentAsString
        val json = objectMapper.readTree(body)
        check(json["error"].asString() == "UNAUTHORIZED")
        check(json["path"].asString() == "/probe/authenticated")
    }

    @TestConfiguration
    class ProbeControllers {
        @Bean
        fun probeController(): Any = ProbeController()
    }

    @RestController
    class ProbeController {
        @GetMapping("/probe/api-error")
        fun apiError(): String =
            throw ApiException(ApiErrorCode.CONFLICT, "conflict boom", mapOf("key" to "value"))

        @PostMapping("/probe/validate")
        fun validate(@Valid @RequestBody request: ValidatedRequest): String = "ok"

        @GetMapping("/probe/boom")
        fun boom(): String = throw IllegalStateException("secret detail must not leak")

        @GetMapping("/probe/authenticated")
        fun authenticated(): String = "secret"
    }

    data class ValidatedRequest(@field:NotBlank val name: String)
}
