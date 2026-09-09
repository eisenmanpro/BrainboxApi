package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.RefreshResponse
import com.afrithecus.brainbox.api.common.error.ApiError
import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.TeacherCodeRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.identity.repository.UserSessionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * End-to-end auth contract tests (doc 01 §1): signup/login/refresh rotation +
 * reuse detection/logout/me, session caps (doc 01 §5) and CTC join (doc 01 §7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthFlowWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val sessionRepository: UserSessionRepository,
    @Autowired private val teacherCodeRepository: TeacherCodeRepository,
) {

    private fun postJson(path: String, body: String): String =
        mockMvc.perform(
            post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        ).andReturn().response.contentAsString

    private fun signup(phone: String, role: String = "STUDENT"): AuthResponse {
        val body = """{"name":"Test User","phoneNumber":"${phone}","password":"password123","role":"${role}"}"""
        val response = mockMvc.perform(
            post("/auth/signup")
                .header("X-Device-Id", "device-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    @Test
    fun `student signup returns contract-shaped response`() {
        val result = signup("0711000001")
        check(result.success)
        check(result.sessionToken != null)
        check(result.refreshToken != null)
        check(result.user.role == "STUDENT")
        check(result.user.studentAdmissionNumber != null)
        check(result.subscription.tier == "BASE")
        check(result.subscription.status == "NONE")
        check(result.accessLevel == "LIMITED") // unverified students capped at BASE
    }

    @Test
    fun `duplicate phone signup conflicts`() {
        signup("0711000002")
        val response = postJson(
            "/auth/signup",
            """{"name":"Dup","phoneNumber":"0711000002","password":"password123","role":"STUDENT"}""",
        )
        val error = objectMapper.readValue(response, ApiError::class.java)
        check(error.error == "CONFLICT")
        check(error.message.contains("already exists"))
    }

    @Test
    fun `login by phone and wrong-password rejection`() {
        signup("0711000003")
        val ok = mockMvc.perform(
            post("/auth/login")
                .header("X-Device-Id", "device-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"0711000003","password":"password123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(ok, AuthResponse::class.java).user.name == "Test User")

        val bad = mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"0711000003","password":"wrong-password"}""")
        ).andExpect(status().isUnauthorized).andReturn().response.contentAsString
        check(objectMapper.readValue(bad, ApiError::class.java).error == "UNAUTHORIZED")
    }

    @Test
    fun `refresh rotates tokens and rejects reuse`() {
        val login = signup("0711000004")

        val refreshed = postJson("/auth/refresh", """{"refreshToken":"${login.refreshToken}"}""")
        val rotated = objectMapper.readValue(refreshed, RefreshResponse::class.java)
        check(rotated.accessToken.isNotBlank())
        check(rotated.refreshToken != login.refreshToken)
        check(rotated.expiresIn == 24L * 60 * 60)

        // Reusing the already-rotated token revokes the whole family.
        val reuse = mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"${login.refreshToken}"}""")
        ).andExpect(status().isUnauthorized).andReturn().response.contentAsString
        check(objectMapper.readValue(reuse, ApiError::class.java).error == "UNAUTHORIZED")

        // The replacement token is dead too (family revoked).
        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"${rotated.refreshToken}"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `logout revokes refresh tokens and deactivates session`() {
        val login = signup("0711000005")
        val access = login.sessionToken!!

        mockMvc.perform(
            post("/auth/logout").header("Authorization", "Bearer " + access)
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"${login.refreshToken}"}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `me returns the current user without a session token`() {
        val login = signup("0711000006")
        val body = mockMvc.perform(
            get("/auth/me").header("Authorization", "Bearer " + login.sessionToken)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val me = objectMapper.readValue(body, AuthResponse::class.java)
        check(me.sessionToken == null)
        check(me.user.phoneNumber == "0711000006")
    }

    @Test
    fun `students keep at most three active sessions across devices`() {
        val login = signup("0711000007")
        val userId = UUID.fromString(login.user.id)
        val identifier = """{"identifier":"0711000007","password":"password123"}"""

        // Signup opened session on device-1; open two more on other devices.
        mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "device-b")
                .contentType(MediaType.APPLICATION_JSON).content(identifier)
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "device-c")
                .contentType(MediaType.APPLICATION_JSON).content(identifier)
        ).andExpect(status().isOk)
        check(sessionRepository.findAllByUserIdAndIsActiveTrue(userId).size == 3)

        // A fourth device evicts the oldest session; the cap stays at 3.
        mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "device-d")
                .contentType(MediaType.APPLICATION_JSON).content(identifier)
        ).andExpect(status().isOk)

        val active = sessionRepository.findAllByUserIdAndIsActiveTrueOrderByLastActiveAtAscIdAsc(userId)
        check(active.size == 3)
        check(active.none { it.deviceId == "device-1" }) // oldest evicted
        check(active.any { it.deviceId == "device-d" })
    }

    @Test
    fun `teachers keep a single active session across devices`() {
        val login = signup("0711000008", role = "TEACHER")
        val userId = UUID.fromString(login.user.id)
        val identifier = """{"identifier":"0711000008","password":"password123"}"""

        mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "teacher-device-1")
                .contentType(MediaType.APPLICATION_JSON).content(identifier)
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/auth/login").header("X-Device-Id", "teacher-device-2")
                .contentType(MediaType.APPLICATION_JSON).content(identifier)
        ).andExpect(status().isOk)

        val active = sessionRepository.findAllByUserIdAndIsActiveTrue(userId)
        check(active.size == 1)
        check(active.single().deviceId == "teacher-device-2") // first device evicted
    }

    @Test
    fun `student joins teacher via valid CTC and rejects unknown codes`() {
        val teacher = UserEntity().apply {
            phoneNumber = "0711999001"
            name = "Teacher One"
            passwordHash = "not-used"
            role = Role.TEACHER
            isVerified = true
        }
        userRepository.save(teacher)
        teacherCodeRepository.save(TeacherCodeEntity().apply {
            code = "AB12CD"
            teacherUserId = teacher.id
        })

        val joined = mockMvc.perform(
            post("/auth/signup")
                .header("X-Device-Id", "device-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Student One","phoneNumber":"0711000009","password":"password123","role":"STUDENT","teacherCode":"ab12cd"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val auth = objectMapper.readValue(joined, AuthResponse::class.java)
        check(auth.user.referredByTeacherCode == "AB12CD")
        check(auth.user.joinedTeacherId == teacher.id.toString())

        val unknown = mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Student Two","phoneNumber":"0711000010","password":"password123","role":"STUDENT","teacherCode":"ZZZZZZ"}""")
        ).andExpect(status().isBadRequest).andReturn().response.contentAsString
        check(objectMapper.readValue(unknown, ApiError::class.java).message.contains("Unknown or inactive"))
    }

    @Test
    fun `parent switches into a linked child session and others are forbidden`() {
        val parent = signup("0711000020", role = "PARENT")
        val child = signup("0711000021")
        val stranger = signup("0711000022")

        val childEntity = userRepository.findById(java.util.UUID.fromString(child.user.id)).orElseThrow()
        childEntity.parentUserId = java.util.UUID.fromString(parent.user.id)
        userRepository.save(childEntity)

        val switched = mockMvc.perform(
            post("/auth/switch-session")
                .header("Authorization", "Bearer " + parent.sessionToken)
                .header("X-Device-Id", "parent-device")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetUserId":"${child.user.id}","targetRole":"STUDENT"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val session = objectMapper.readValue(switched, AuthResponse::class.java)
        check(session.user.id == child.user.id)
        check(session.sessionToken != parent.sessionToken)

        val me = mockMvc.perform(
            get("/auth/me").header("Authorization", "Bearer " + session.sessionToken)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(me, AuthResponse::class.java).user.phoneNumber == "0711000021")

        mockMvc.perform(
            post("/auth/switch-session")
                .header("Authorization", "Bearer " + stranger.sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetUserId":"${child.user.id}","targetRole":"STUDENT"}""")
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/auth/switch-session")
                .header("Authorization", "Bearer " + parent.sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"targetUserId":"${stranger.user.id}","targetRole":"STUDENT"}""")
        ).andExpect(status().isForbidden)
    }
}
