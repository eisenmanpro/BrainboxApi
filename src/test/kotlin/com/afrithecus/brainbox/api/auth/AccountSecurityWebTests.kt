package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRegistrationRequestRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SchoolRegistrationRequestView
import org.junit.jupiter.api.BeforeEach
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

/** POST auth/change-password and POST auth/register-school (V50). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountSecurityWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val registrationRepository: SchoolRegistrationRequestRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var admin: UserEntity
    private lateinit var learner: UserEntity

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        admin = user(Role.ADMIN, "Admin", "0755400001")
        learner = user(Role.STUDENT, "Learner", "0755400002", grade = "Grade 4")
    }

    private fun user(role: Role, name: String, phone: String, grade: String? = null): UserEntity {
        val entity = UserEntity()
        entity.phoneNumber = phone
        entity.email = phone + "@security.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = name
        entity.role = role
        entity.schoolId = school.id
        entity.gradeLevel = grade
        entity.isActive = true
        entity.isVerified = true
        return userRepository.save(entity)
    }

    private fun login(identifier: String, password: String = "password123", deviceId: String? = null): AuthResponse {
        val builder = post("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}")
        deviceId?.let { builder.header("X-Device-Id", it) }
        val response = mockMvc.perform(builder).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun changePassword(token: String, current: String, new: String): AuthResponse {
        val response = mockMvc.perform(
            post("/auth/change-password").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + new + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    @Test
    fun `change password validates, updates the credential and revokes other sessions`() {
        val first = login("0755400002", deviceId = "device-a")
        val second = login("0755400002", deviceId = "device-b")

        val wrong = changePassword(first.sessionToken!!, "not-the-password", "newpassword1")
        check(!wrong.success && wrong.message == "Current password is incorrect.")
        val short = changePassword(first.sessionToken, "password123", "short")
        check(!short.success && short.message!!.contains("at least 8"))
        val same = changePassword(first.sessionToken, "password123", "password123")
        check(!same.success && same.message!!.contains("differ"))

        val changed = changePassword(first.sessionToken, "password123", "newpassword1")
        check(changed.success && changed.message == "Password changed successfully.")

        // Old credential no longer logs in; the new one does.
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0755400002\",\"password\":\"password123\"}")
        ).andExpect(status().isUnauthorized)
        login("0755400002", "newpassword1")

        // The other device's refresh token is revoked; the caller's session survives.
        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + second.refreshToken + "\"}")
        ).andExpect(status().isUnauthorized)
        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + first.refreshToken + "\"}")
        ).andExpect(status().isOk)
    }

    @Test
    fun `register school queues for review and admin approval creates it`() {
        val token = login("0755400002").sessionToken!!
        val body = "{\"requestId\":\"SR-1\",\"schoolName\":\"Moi Avenue School\",\"address\":\"Nairobi\",\"submittedBy\":\"ignored\"}"

        val first = objectMapper.readValue(
            mockMvc.perform(
                post("/auth/register-school").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(first.success && first.message.contains("pending verification"))
        // Replaying the same request id is idempotent.
        mockMvc.perform(
            post("/auth/register-school").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk)
        check(registrationRepository.findAll().size == 1)

        val adminToken = login("0755400001").sessionToken!!
        // Non-admin cannot review.
        mockMvc.perform(get("/admin/school-registration-requests").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden)

        val pending = objectMapper.readValue(
            mockMvc.perform(get("/admin/school-registration-requests").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            Array<SchoolRegistrationRequestView>::class.java,
        )
        check(pending.size == 1 && pending.single().status == "PENDING")

        val approved = objectMapper.readValue(
            mockMvc.perform(
                post("/admin/school-registration-requests/" + pending.single().id + "/approve")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"verified\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            SchoolRegistrationRequestView::class.java,
        )
        check(approved.status == "APPROVED" && approved.reviewNote == "verified")
        val created = schoolRepository.findByNameIgnoreCase("Moi Avenue School")
        check(created != null && created.isActive)

        // Replaying the decision keeps a terminal status.
        mockMvc.perform(
            post("/admin/school-registration-requests/" + pending.single().id + "/approve")
                .header("Authorization", "Bearer " + adminToken)
        ).andExpect(status().isOk)
    }

    @Test
    fun `register school rejects a duplicate active name`() {
        val token = login("0755400002").sessionToken!!
        val response = objectMapper.readValue(
            mockMvc.perform(
                post("/auth/register-school").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"SR-2\",\"schoolName\":\"" + school.name + "\"}")
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            AuthResponse::class.java,
        )
        check(!response.success && response.message.contains("already registered"))
    }
}
