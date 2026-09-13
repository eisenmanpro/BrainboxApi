package com.afrithecus.brainbox.api.feedback

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.feedback.web.FeedbackTemplatePayload
import com.afrithecus.brainbox.api.feedback.web.TeacherFeedbackPayload
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/** Teacher feedback templates, history and bulk submit (doc 04 section 7). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FeedbackWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {
    private fun user(role: Role, name: String, phone: String): UserEntity = userRepository.save(UserEntity().apply {
        phoneNumber = phone
        email = phone + "@feedback.test"
        passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        this.name = name
        this.role = role
        isActive = true
        isVerified = true
    })

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    @Test
    fun `templates and feedback submit and replay idempotently`() {
        val teacher = user(Role.TEACHER, "Class Teacher", "0755060001")
        val other = user(Role.TEACHER, "Other Teacher", "0755060002")
        val student = user(Role.STUDENT, "Alice Learner", "0755060003")
        val t = token(teacher)

        val template = FeedbackTemplatePayload(
            id = "ft_1", title = "Great work", content = "Keep it up", category = "Encouragement",
        )
        mockMvc.perform(post("/teacher/feedback/templates").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(template)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/teacher/feedback/templates").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(template)))
            .andExpect(status().isOk)
        val templates = objectMapper.readValue(
            mockMvc.perform(get("/teacher/feedback/templates").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<FeedbackTemplatePayload>::class.java,
        )
        check(templates.size == 1)
        check(templates.single().category == "Encouragement")

        val feedback = TeacherFeedbackPayload(
            id = "fb_1", submissionId = "sub_1", studentId = student.id.toString(),
            textFeedback = "Good structure", photoFeedbackUrls = listOf("https://cdn.test/a.png"),
            rubricScores = mapOf("Clarity" to 4),
        )
        val saved = objectMapper.readValue(
            mockMvc.perform(post("/teacher/feedback").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(feedback)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            TeacherFeedbackPayload::class.java,
        )
        check(saved.id == "fb_1")
        check(saved.teacherId == teacher.id.toString())
        check(saved.rubricScores["Clarity"] == 4)
        check(saved.photoFeedbackUrls == listOf("https://cdn.test/a.png"))
        check(saved.createdAt > 0)

        // Replay of the same client id upserts rather than duplicating.
        mockMvc.perform(post("/teacher/feedback").header("Authorization", auth(t))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(feedback)))
            .andExpect(status().isOk)
        val history = objectMapper.readValue(
            mockMvc.perform(get("/teacher/feedback/history").header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherFeedbackPayload>::class.java,
        )
        check(history.size == 1)

        // Bulk submit stores each row.
        val bulk = listOf(
            feedback.copy(id = "fb_2", studentId = student.id.toString()),
            feedback.copy(id = "fb_3", studentId = student.id.toString()),
        )
        val bulkSaved = objectMapper.readValue(
            mockMvc.perform(post("/teacher/feedback/bulk").header("Authorization", auth(t))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(bulk)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherFeedbackPayload>::class.java,
        )
        check(bulkSaved.size == 2)
        val filtered = objectMapper.readValue(
            mockMvc.perform(get("/teacher/feedback/history?studentId=" + student.id).header("Authorization", auth(t)))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<TeacherFeedbackPayload>::class.java,
        )
        check(filtered.size == 3)

        // Ownership and role gates.
        mockMvc.perform(delete("/teacher/feedback/templates/ft_1").header("Authorization", auth(token(other))))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/teacher/feedback/history").header("Authorization", auth(token(student))))
            .andExpect(status().isForbidden)

        mockMvc.perform(delete("/teacher/feedback/templates/ft_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
        mockMvc.perform(delete("/teacher/feedback/templates/ft_1").header("Authorization", auth(t)))
            .andExpect(status().isNoContent)
    }
}
