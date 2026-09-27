package com.afrithecus.brainbox.api.ml

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * With no model deployed the routes must still be reachable so a client can tell
 * "not available" (and fall back) instead of chasing an authentication failure.
 * The app asks for the model on startup, before any session exists.
 */
@SpringBootTest(properties = ["app.ml.model.path="])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModelDistributionWebTests {

    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    fun `the manifest route is public and reports unavailability`() {
        mockMvc.perform(get("/models/adaptive-difficulty"))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `the model file route is public and reports unavailability`() {
        mockMvc.perform(get("/models/adaptive-difficulty/file"))
            .andExpect(status().isServiceUnavailable)
    }
}
