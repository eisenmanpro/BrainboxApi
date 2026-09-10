package com.afrithecus.brainbox.api.profile

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.TeacherProfileEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.TeacherProfileRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.profile.web.UserSettingsPayload
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Profile settings (BACKEND_BLUEPRINT §13): server-derived read model, PATCH
 * persistence, and strict self-only JWT scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfileWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val teacherProfileRepository: TeacherProfileRepository,
    @Autowired private val teacherCodeRepository: TeacherCodeRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun signup(phone: String): AuthResponse {
        val body = """{"name":"Profile ${phone}","phoneNumber":"${phone}","password":"password123","role":"STUDENT"}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun loginTeacher(phone: String, email: String): AuthResponse {
        val teacher = UserEntity().apply {
            this.phoneNumber = phone
            this.email = email
            passwordHash = passwordEncoder.encode("teacherpass123") ?: error("encode")
            name = "Profile Teacher"
            role = Role.TEACHER
            isActive = true
            isVerified = true
        }
        userRepository.save(teacher)
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"${email}","password":"teacherpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(login, AuthResponse::class.java)
    }

    private fun auth(token: String) = "Bearer " + token

    @Test
    fun `student settings expose server defaults and patch persists`() {
        val student = signup("0778100001")
        val id = student.user.id
        val body = mockMvc.perform(get("/profile/settings/${id}").header("Authorization", auth(student.sessionToken!!)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val defaults = objectMapper.readValue(body, UserSettingsPayload::class.java)
        check(defaults.plan == "BASE")
        check(defaults.subscriptionStatus == "NONE")
        check(defaults.subscriptionExpiry == null)
        check(defaults.roleLabel == "Student")
        check(defaults.preferredDifficulty == "Medium")
        check(defaults.theme == "dark")
        check(defaults.competencyFocus.size == 4)
        check(defaults.competencyFocus["Critical Thinking"] == 50)
        check(defaults.fullName == student.user.name)

        val patchBody = """
            {"fullName":"Renamed Student","email":"renamed@profile.test",
             "preferredDifficulty":"Hard","aiSensitivity":5,"dailyReminderEnabled":false,
             "competencyFocus":{"Critical Thinking":80,"Communication":60}}
        """.trimIndent()
        val updatedBody = mockMvc.perform(
            patch("/profile/settings/${id}").header("Authorization", auth(student.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content(patchBody)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val updated = objectMapper.readValue(updatedBody, UserSettingsPayload::class.java)
        check(updated.fullName == "Renamed Student")
        check(updated.email == "renamed@profile.test")
        check(updated.preferredDifficulty == "Hard")
        check(updated.aiSensitivity == 5)
        check(!updated.dailyReminderEnabled)
        check(updated.competencyFocus["Critical Thinking"] == 80)
        check(updated.competencyFocus["Communication"] == 60)

        val persisted = userRepository.findById(UUID.fromString(id)).orElseThrow()
        check(persisted.name == "Renamed Student")
        check(persisted.email == "renamed@profile.test")
    }

    @Test
    fun `settings are self-only and anonymous callers are unauthorized`() {
        val a = signup("0778100002")
        val b = signup("0778100003")
        mockMvc.perform(get("/profile/settings/${b.user.id}").header("Authorization", auth(a.sessionToken!!)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            patch("/profile/settings/${b.user.id}").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"preferredDifficulty":"Easy"}""")
        ).andExpect(status().isForbidden)
        mockMvc.perform(get("/profile/settings/${a.user.id}")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `out-of-range sensitivity and duplicate email are rejected`() {
        val a = signup("0778100004")
        val b = signup("0778100005")
        mockMvc.perform(
            patch("/profile/settings/${a.user.id}").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"aiSensitivity":9}""")
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            patch("/profile/settings/${a.user.id}").header("Authorization", auth(a.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"shared@profile.test"}""")
        ).andExpect(status().isOk)
        mockMvc.perform(
            patch("/profile/settings/${b.user.id}").header("Authorization", auth(b.sessionToken!!))
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"shared@profile.test"}""")
        ).andExpect(status().isConflict)
    }

    @Test
    fun `teacher settings include code, subject and teacher role label`() {
        val teacher = loginTeacher("0778199001", "subject.teacher@profile.test")
        val teacherId = UUID.fromString(teacher.user.id)
        teacherProfileRepository.save(TeacherProfileEntity().apply {
            this.userId = teacherId
            this.subject = "Physics"
        })
        teacherCodeRepository.save(TeacherCodeEntity().apply {
            this.code = "ABC12345"
            this.teacherUserId = teacherId
            this.active = true
        })
        val body = mockMvc.perform(
            get("/profile/settings/${teacher.user.id}").header("Authorization", auth(teacher.sessionToken!!))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val settings = objectMapper.readValue(body, UserSettingsPayload::class.java)
        check(settings.roleLabel == "Teacher")
        check(settings.teacherCode == "ABC12345")
        check(settings.subjects == listOf("Physics"))
    }
}
