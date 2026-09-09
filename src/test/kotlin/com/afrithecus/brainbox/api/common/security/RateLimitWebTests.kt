package com.afrithecus.brainbox.api.common.security

import com.afrithecus.brainbox.api.common.error.ApiError
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

/** Rate limiter: burst capacity 3 with 429 + Retry-After once exhausted. */
@SpringBootTest(
    properties = [
        "app.security.rate-limit.enabled=true",
        "app.security.rate-limit.capacity=3",
        "app.security.rate-limit.refill-per-second=1",
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {

    @Test
    fun `bursts past the capacity get 429 with Retry-After`() {
        repeat(3) {
            mockMvc.perform(
                post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"identifier":"0710000001","password":"wrong"}""")
            ).andExpect(status().isUnauthorized)
        }

        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"0710000001","password":"wrong"}""")
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Retry-After", "60"))
            .andReturn().response.contentAsString

        check(objectMapper.readValue(body, ApiError::class.java).error == "TOO_MANY_REQUESTS")
    }
}
