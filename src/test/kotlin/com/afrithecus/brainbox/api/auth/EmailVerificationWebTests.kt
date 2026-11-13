package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.TeacherSignupRequest
import com.afrithecus.brainbox.api.auth.web.TeacherSignupResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolConfigEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubRole
import com.afrithecus.brainbox.api.identity.repository.SchoolConfigRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Email verification, the account-state gate and the public signup directory (gaps.md 1C/1D/3).
 * Signup must not yield a session; a pending account must not reach data even with a token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(CapturingEmailConfig::class)
class EmailVerificationWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val schoolConfigRepository: SchoolConfigRepository,
    @Autowired private val settingsRepository: SchoolSystemSettingsRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val sender: CapturingEmailSender,
) {

    private lateinit var school: SchoolEntity

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        schoolConfigRepository.save(
            SchoolConfigEntity().apply {
                schoolId = school.id
                schoolName = school.name
                classes = """[{"name":"Grade 4 East","gradeLevel":"Grade 4"},{"name":"Grade 5 West","gradeLevel":"Grade 5"}]"""
            }
        )
    }

    private fun request(
        email: String = "new.teacher@brainbox.test",
        phone: String = "0755300001",
        subRole: String? = null,
        grades: List<String> = listOf("Grade 4"),
        className: String = "",
    ) = TeacherSignupRequest(
        name = "New Teacher",
        phoneNumber = phone,
        password = "password123",
        email = email,
        schoolId = school.id.toString(),
        subRole = subRole,
        grades = grades,
        className = className,
    )

    private fun signup(body: TeacherSignupRequest): TeacherSignupResponse {
        val response = mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
        ).andReturn().response
        check(response.status == 200) { "signup failed: " + response.status + " " + response.contentAsString }
        return objectMapper.readValue(response.contentAsString, TeacherSignupResponse::class.java)
    }

    private fun verify(email: String, code: String): AuthResponse {
        val response = mockMvc.perform(
            post("/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"code\":\"" + code + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java)
    }

    private fun errorCode(body: String): String {
        val node = objectMapper.readValue(body, Map::class.java)
        return node["error"] as String
    }

    @Test
    fun `signup emails a code and gives no session until it is confirmed`() {
        val signup = signup(request())
        check(signup.success)
        check(signup.emailVerificationRequired)
        check(signup.sessionToken == null) { "an unverified signup must not get a session token" }
        check(signup.maskedEmail == "n***@brainbox.test") { "got " + signup.maskedEmail }
        check(signup.user.email == "new.teacher@brainbox.test" && !signup.user.emailVerified)

        // Correct password, unconfirmed address: a distinct, machine-readable refusal.
        val denied = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"new.teacher@brainbox.test\",\"password\":\"password123\"}")
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(errorCode(denied) == "EMAIL_NOT_VERIFIED") { "got " + denied }

        // A wrong code is rejected without revealing anything.
        mockMvc.perform(
            post("/auth/email/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"new.teacher@brainbox.test\",\"code\":\"000000\"}")
        ).andExpect(status().isBadRequest)

        val verified = verify("new.teacher@brainbox.test", sender.codeFor("new.teacher@brainbox.test"))
        check(verified.sessionToken != null && verified.user.emailVerified)

        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"new.teacher@brainbox.test\",\"password\":\"password123\"}")
        ).andExpect(status().isOk)

        // A pending account can read its own state but no school data, even with a token.
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + verified.sessionToken))
            .andExpect(status().isOk)
        val blocked = mockMvc.perform(
            get("/profile/settings/" + verified.user.id).header("Authorization", "Bearer " + verified.sessionToken)
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(errorCode(blocked) == "ACCOUNT_PENDING") { "got " + blocked }
    }

    @Test
    fun `resending a code inside the cool-down is throttled`() {
        signup(request())
        mockMvc.perform(
            post("/auth/email/send-code").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"new.teacher@brainbox.test\"}")
        ).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `signup requires a school and a configured grade and class`() {
        // No school: refused before anything is created.
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request().copy(schoolId = null)
                    )
                )
        ).andExpect(status().isBadRequest)

        // Class teacher with a class that is not in the school catalogue.
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("class.t@brainbox.test", "0755300002", "CTEACHER", listOf("Grade 4"), "Grade 4 Purple")
                    )
                )
        ).andExpect(status().isBadRequest)

        // A grade that the school does not offer.
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("grade.t@brainbox.test", "0755300003", null, listOf("Grade 9"), "")
                    )
                )
        ).andExpect(status().isBadRequest)

        // ICT admin does not take a grade or a class.
        mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("admin.t@brainbox.test", "0755300004", "ICT_ADMIN", listOf("Grade 4"), "")
                    )
                )
        ).andExpect(status().isBadRequest)

        // The valid class-teacher shape is accepted.
        val ok = signup(request("ok.t@brainbox.test", "0755300005", "CTEACHER", listOf("Grade 4"), "Grade 4 East"))
        check(ok.user.teacherSubRole == "CTEACHER")
        check(ok.user.className == "Grade 4 East")
    }

    @Test
    fun `a second ICT admin and a closed school are refused with the server message`() {
        val first = UserEntity()
        first.phoneNumber = "0755300010"
        first.email = "ict@brainbox.test"
        first.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        first.name = "ICT Admin"
        first.role = Role.TEACHER
        first.subRole = SubRole.ICT_ADMIN
        first.schoolId = school.id
        first.isActive = true
        first.isVerified = true
        userRepository.save(first)

        val secondAdmin = mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("second.admin@brainbox.test", "0755300011", "ICT_ADMIN", emptyList(), "")
                    )
                )
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(secondAdmin.contains("already has an ICT admin")) { "got " + secondAdmin }

        settingsRepository.saveAndFlush(SchoolSystemSettingsEntity().apply {
            schoolId = school.id
            registrationOpen = false
        })
        val closed = mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("closed@brainbox.test", "0755300012", null, listOf("Grade 4"), "")
                    )
                )
        ).andExpect(status().isForbidden).andReturn().response.contentAsString
        check(closed.contains("currently closed")) { "got " + closed }
    }

    @Test
    fun `the signup directory returns schools and their configured grades and classes`() {
        val schools = mockMvc.perform(get("/auth/signup/schools")).andExpect(status().isOk)
            .andReturn().response.contentAsString
        check(schools.contains(school.id.toString()) && schools.contains("Alliance High School"))

        val detail = mockMvc.perform(get("/auth/signup/schools/" + school.id))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(detail.contains("Grade 4") && detail.contains("Grade 4 East") && detail.contains("Grade 5 West"))
        check(detail.contains("registrationOpen\":true")) { "got " + detail }
    }

    @Test
    fun `recover email returns only the masked address`() {
        val user = UserEntity()
        user.phoneNumber = "0755300020"
        user.email = "recover.me@brainbox.test"
        user.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        user.name = "Recover Me"
        user.role = Role.TEACHER
        user.schoolId = school.id
        user.isActive = true
        user.isVerified = true
        userRepository.save(user)

        val found = mockMvc.perform(
            post("/auth/recover-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0755300020\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(found.contains("\"found\":true") && found.contains("r***@brainbox.test")) { "got " + found }
        check(!found.contains("recover.me@brainbox.test")) { "the full address must never be returned" }

        val unknown = mockMvc.perform(
            post("/auth/recover-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"0700000000\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(unknown.contains("\"found\":false")) { "got " + unknown }
    }

    @Test
    fun `the first ICT admin of a school can sign up but a school with no catalogue cannot take a class teacher`() {
        val admin = signup(request("first.admin@brainbox.test", "0755300040", "ICT_ADMIN", emptyList(), ""))
        check(admin.user.teacherSubRole == "ICT_ADMIN")
        check(admin.emailVerificationRequired && admin.sessionToken == null)

        val bare = schoolRepository.save(SchoolEntity().apply { name = "Bare School"; isActive = true })
        val response = mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("bare.t@brainbox.test", "0755300041", "CTEACHER", listOf("Grade 4"), "Grade 4 East")
                            .copy(schoolId = bare.id.toString())
                    )
                )
        ).andReturn().response
        check(response.status == 400) { "got " + response.status + " " + response.contentAsString }
        check(response.contentAsString.contains("not set up")) { "got " + response.contentAsString }
    }

    @Test
    fun `a duplicate email is refused case-insensitively`() {
        signup(request("dup@brainbox.test", "0755300030"))
        val response = mockMvc.perform(
            post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        request("DUP@BrainBox.test", "0755300031", null, listOf("Grade 4"), "")
                    )
                )
        ).andReturn().response
        check(response.status == 409) { "expected 409, got " + response.status + " " + response.contentAsString }
    }
}
