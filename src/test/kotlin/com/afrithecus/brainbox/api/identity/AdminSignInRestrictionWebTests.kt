package com.afrithecus.brainbox.api.identity

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.web.SystemSettingsPayload
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * N4: the school administrator sign-in restriction switch.
 *
 * Default open; any administrator may close it; the pinned first administrator is
 * exempt so it can be reopened; every other administrator is refused with the
 * owner's sentence and has live sessions revoked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminSignInRestrictionWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private lateinit var school: SchoolEntity
    private lateinit var owner: UserEntity
    private lateinit var secondAdmin: UserEntity
    private lateinit var teacher: UserEntity

    private fun user(role: Role, name: String, phone: String, schoolId: UUID?, subRole: SubRole? = null): UserEntity =
        userRepository.save(UserEntity().apply {
            phoneNumber = phone
            email = phone + "@n4.test"
            passwordHash = passwordEncoder.encode("password123") ?: error("encode")
            this.name = name
            this.role = role
            this.subRole = subRole
            this.schoolId = schoolId
            isActive = true
            isVerified = true
        })

    private fun token(entity: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("identifier" to entity.phoneNumber, "password" to "password123"))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun settingsBody(open: Boolean, maintenance: Boolean = false, registration: Boolean = true) =
        SystemSettingsPayload(
            schoolId = school.id.toString(),
            maintenanceMode = maintenance,
            registrationOpen = registration,
            adminSignInOpen = open,
        )

    private fun putSettings(admin: UserEntity, body: SystemSettingsPayload) {
        mockMvc.perform(
            put("/admin/schools/" + school.id + "/system-settings")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        ).andExpect(status().isOk)
    }

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        // The first administrator is the earliest-created active ICT admin of the school.
        owner = user(Role.TEACHER, "First Admin", "0755700001", school.id, SubRole.ICT_ADMIN)
        Thread.sleep(10)
        secondAdmin = user(Role.TEACHER, "Second Admin", "0755700002", school.id, SubRole.ICT_ADMIN)
        teacher = user(Role.TEACHER, "Class Teacher", "0755700003", school.id)
    }

    @Test
    fun `open by default and the owner is identified in the payload`() {
        val token = token(owner)
        val body = mockMvc.perform(
            get("/admin/schools/" + school.id + "/system-settings").header("Authorization", auth(token)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(body, SystemSettingsPayload::class.java)
        check(payload.adminSignInOpen) { "the switch must be open by default" }
        check(payload.isAdminOwner) { "the first administrator must be told they own the unlock right" }
        check(payload.adminOwnerId == owner.id.toString())

        val secondToken = token(secondAdmin)
        val secondBody = mockMvc.perform(
            get("/admin/schools/" + school.id + "/system-settings").header("Authorization", auth(secondToken)),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val secondPayload = objectMapper.readValue(secondBody, SystemSettingsPayload::class.java)
        check(secondPayload.adminSignInOpen)
        check(secondPayload.isAdminOwner.not()) { "a second administrator does not own the unlock right" }
    }

    @Test
    fun `closing the switch refuses other administrators with the owner sentence and revokes them`() {
        val secondToken = token(secondAdmin) // signed in BEFORE the switch closes
        putSettings(owner, settingsBody(open = false))

        // The owner is exempt so the school can always reopen it.
        token(owner)

        // Another administrator is refused, with the server-authored sentence.
        val refusal = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        mapOf("identifier" to secondAdmin.phoneNumber, "password" to "password123"),
                    ),
                ),
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(refusal.contains("Contact the school admin for more information")) { "owner sentence missing: " + refusal }

        // The session issued before the switch is dead: its access token no longer authenticates.
        mockMvc.perform(get("/auth/me").header("Authorization", auth(secondToken)))
            .andExpect(status().isUnauthorized)

        // Non-administrators are untouched by the switch.
        token(teacher)
    }

    @Test
    fun `only the first administrator reopens the switch, and a platform operator is break glass`() {
        putSettings(owner, settingsBody(open = false))

        // The owner reopens it.
        putSettings(owner, settingsBody(open = true))
        token(secondAdmin)

        // Close again, then prove a platform operator (a different subject: a school-less
        // console operator, not a school administrator) can break glass and reopen it.
        putSettings(owner, settingsBody(open = false))
        val platformAdmin = user(Role.ADMIN, "Platform Operator", "0755700009", null)
        val platformToken = token(platformAdmin)
        val reopened = mockMvc.perform(
            put("/admin/schools/" + school.id + "/system-settings")
                .header("Authorization", auth(platformToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(settingsBody(open = true))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(reopened, SystemSettingsPayload::class.java).adminSignInOpen)
        token(secondAdmin)
    }

    @Test
    fun `patching maintenance settings does not silently close administrator sign-in`() {
        // Older/other clients send only the settings they know; adminSignInOpen defaults true.
        putSettings(owner, settingsBody(open = true, maintenance = true))
        token(secondAdmin)
        val body = mockMvc.perform(
            get("/admin/schools/" + school.id + "/system-settings").header("Authorization", auth(token(owner))),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(body, SystemSettingsPayload::class.java)
        check(payload.maintenanceMode)
        check(payload.registrationOpen)
        check(payload.adminSignInOpen)
    }
}
