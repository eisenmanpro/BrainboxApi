package com.afrithecus.brainbox.api.common.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Every response carries the hardened header set. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersWebTests(@Autowired private val mockMvc: MockMvc) {

    @Test
    fun `api responses carry hardened security headers`() {
        // Unauthenticated request to a protected route still passes through
        // the header writers before the 401 entry point.
        mockMvc.perform(get("/exams"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Permissions-Policy"))
    }
}
