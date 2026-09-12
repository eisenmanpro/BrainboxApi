package com.afrithecus.brainbox.api.identity.web

import com.afrithecus.brainbox.api.auth.web.AuthResponse
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Admin identity contract tests (doc 01 §2.2/§6.1/§7.2/§8.2/§9.2), including
 * RBAC guards (student => 403) and public school discovery.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminIdentityWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private fun json(path: String, method: (String) -> org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, body: String): String =
        mockMvc.perform(method(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn().response.contentAsString

    private fun signup(phone: String, role: String = "STUDENT", extra: String = ""): AuthResponse {
        val body = """{"name":"User ${phone}","phoneNumber":"${phone}","password":"password123","role":"${role}"${
            if (extra.isBlank()) "" else "," + extra
        }}"""
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "dev")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun newAdmin(): AuthResponse {
        val admin = UserEntity().apply {
            phoneNumber = "0799000000"
            email = "admin@brainbox.test"
            passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
            name = "Platform Admin"
            role = Role.ADMIN
            isActive = true
            isVerified = true
        }
        userRepository.save(admin)
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"admin@brainbox.test","password":"adminpass123"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun bearer(token: String?) = "Bearer " + token!!

    @Test
    fun `admin lists users and non admins are forbidden`() {
        val admin = newAdmin()
        signup("0722000001")

        val list = mockMvc.perform(
            get("/admin/users?page=0&limit=10").header("Authorization", bearer(admin.sessionToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val result = objectMapper.readValue(list, AdminUserListResponse::class.java)
        check(result.total >= 2)
        check(result.users.any { it.role == "ADMIN" })
        check(result.users.any { it.role == "STUDENT" })

        val student = signup("0722000002")
        mockMvc.perform(
            get("/admin/users").header("Authorization", bearer(student.sessionToken))
        ).andExpect(status().isForbidden)

        mockMvc.perform(get("/admin/users")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `approval plus paid tier unlocks full access`() {
        val admin = newAdmin()
        val student = signup("0722000003")
        check(student.accessLevel == "LIMITED")

        mockMvc.perform(
            post("/admin/users/${student.user.id}/approve").header("Authorization", bearer(admin.sessionToken))
        ).andExpect(status().isNoContent)
        mockMvc.perform(
            post("/admin/users/${student.user.id}/subscription").header("Authorization", bearer(admin.sessionToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"EXPLORER","expiryDate":${Instant.now().plusSeconds(30 * 24 * 3600).toEpochMilli()}}""")
        ).andExpect(status().isNoContent)

        val me = mockMvc.perform(
            get("/auth/me").header("Authorization", bearer(student.sessionToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(me, AuthResponse::class.java)
        check(payload.accessLevel == "FULL")
    }

    @Test
    fun `admin links a parent to a child and the parent sees children`() {
        val admin = newAdmin()
        val parent = signup("0722000004", role = "PARENT")
        val child = signup("0722000005")

        mockMvc.perform(
            post("/admin/parent-link").header("Authorization", bearer(admin.sessionToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"parentId":"${parent.user.id}","childId":"${child.user.id}"}""")
        ).andExpect(status().isNoContent)

        val me = mockMvc.perform(
            get("/auth/me").header("Authorization", bearer(parent.sessionToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val payload = objectMapper.readValue(me, AuthResponse::class.java)
        check(payload.linkedChildren?.size == 1)
        check(payload.linkedChildren!!.single().id == child.user.id)
    }

    @Test
    fun `admin creates teacher with server issued code and student can join`() {
        val admin = newAdmin()
        val student = signup("0722000006", extra = """"schoolName":"CTC High"""")
        val schoolId = student.user.schoolId!!

        val created = mockMvc.perform(
            post("/admin/schools/${schoolId}/teachers").header("Authorization", bearer(admin.sessionToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Grace Teacher","email":"grace@ctc.test","phoneNumber":"0722777001","subject":"Mathematics"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val teacher = objectMapper.readValue(created, TeacherPayload::class.java)
        check(teacher.teacherCode!!.length == 6)
        check(teacher.subject == "Mathematics")

        val joined = signup("0722000007", extra = """"schoolName":"CTC High","teacherCode":"${teacher.teacherCode}"""")
        check(joined.user.joinedTeacherId == teacher.id)
    }

    @Test
    fun `removing a teacher revokes their code`() {
        val admin = newAdmin()
        val student = signup("0722000008", extra = """"schoolName":"Revoke High"""")
        val schoolId = student.user.schoolId!!
        val created = mockMvc.perform(
            post("/admin/schools/${schoolId}/teachers").header("Authorization", bearer(admin.sessionToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Gone Teacher","email":"gone@revoke.test","phoneNumber":"0722777002"}""")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val teacher = objectMapper.readValue(created, TeacherPayload::class.java)

        mockMvc.perform(
            delete("/admin/schools/${schoolId}/teachers/${teacher.id}").header("Authorization", bearer(admin.sessionToken))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Late Joiner","phoneNumber":"0722000009","password":"password123","role":"STUDENT","teacherCode":"${teacher.teacherCode}"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `admin resets a password to a usable credential`() {
        val admin = newAdmin()
        val student = signup("0722000010")

        val reset = mockMvc.perform(
            post("/admin/users/${student.user.id}/reset-password").header("Authorization", bearer(admin.sessionToken))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val freshPassword = objectMapper.readValue(reset, ResetPasswordResponse::class.java).password
        check(freshPassword.length == 12)

        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"0722000010","password":"${freshPassword}"}""")
        ).andExpect(status().isOk)
    }

    @Test
    fun `subscription update rejects unknown tiers`() {
        val admin = newAdmin()
        val student = signup("0722000011")
        mockMvc.perform(
            post("/admin/users/${student.user.id}/subscription").header("Authorization", bearer(admin.sessionToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tier":"ULTRA"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `public school discovery works without a token and detail needs auth`() {
        signup("0722000012", extra = """"schoolName":"Discovery Academy"""")
        signup("0722000013", extra = """"schoolName":"Other Academy"""")

        val all = mockMvc.perform(get("/schools/all")).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val allSchools = objectMapper.readValue(all, Array<SchoolListPayload>::class.java)
        check(allSchools.any { it.name == "Discovery Academy" })
        check(allSchools.any { it.name == "Other Academy" })

        val search = mockMvc.perform(get("/schools/search?query=Discovery"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val found = objectMapper.readValue(search, Array<SchoolListPayload>::class.java)
        check(found.any { it.name == "Discovery Academy" })
        check(found.none { it.name == "Other Academy" })

        // School detail is public too (landing/web school browsing pre-auth).
        val schoolId = found.first { it.name == "Discovery Academy" }.id
        val detail = mockMvc.perform(get("/schools/${schoolId}"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(objectMapper.readValue(detail, SchoolDetailPayload::class.java).basicInfo.name == "Discovery Academy")
        // Invalid identifiers return the public validation error, not 401.
        mockMvc.perform(get("/schools/some-id")).andExpect(status().isBadRequest)
    }
}
