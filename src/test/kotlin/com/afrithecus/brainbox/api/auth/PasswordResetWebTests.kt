package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.TeacherSignupRequest
import com.afrithecus.brainbox.api.auth.web.VerifyOtpPayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolSystemSettingsRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.security.AuthThrottle
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Password recovery, throttle and registration gate (docs/ongoing/api_auth_changes.md). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PasswordResetWebTests.NotifierConfig::class)
class PasswordResetWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val settingsRepository: SchoolSystemSettingsRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val notifier: PasswordResetNotifier,
    @Autowired private val throttle: AuthThrottle,
) {

    private lateinit var school: SchoolEntity
    private lateinit var user: UserEntity

    @TestConfiguration
    class NotifierConfig {
        @Bean
        @Primary
        fun passwordResetNotifier(): PasswordResetNotifier = CapturingPasswordResetNotifier()
    }

    class CapturingPasswordResetNotifier : PasswordResetNotifier {
        val codes = ConcurrentHashMap<String, String>()
        override fun send(user: UserEntity, otp: String) {
            codes[user.id.toString()] = otp
        }
    }

    private fun capturedOtp(): String = (notifier as CapturingPasswordResetNotifier).codes[user.id.toString()]!!

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        val entity = UserEntity()
        entity.phoneNumber = "0755600001"
        entity.email = "reset@auth.test"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = "Reset User"
        entity.role = Role.STUDENT
        entity.schoolId = school.id
        entity.isActive = true
        entity.isVerified = true
        user = userRepository.save(entity)
    }

    private data class MessageResult(val success: Boolean, val message: String)

    private fun forgot(identifier: String): MessageResult {
        val response = mockMvc.perform(
            post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, MessageResult::class.java)
    }

    private fun verify(identifier: String, otp: String): VerifyOtpPayload {
        val response = mockMvc.perform(
            post("/auth/verify-otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"otp\":\"" + otp + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, VerifyOtpPayload::class.java)
    }

    private fun reset(token: String, newPassword: String): MessageResult {
        val response = mockMvc.perform(
            post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"resetToken\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, MessageResult::class.java)
    }

    private fun login(identifier: String, password: String): Int {
        return mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}")
        ).andReturn().response.status
    }

    @Test
    fun `forgot password is neutral for known and unknown identifiers`() {
        val known = forgot(user.phoneNumber!!)
        val unknown = forgot("0700000000")
        check(known.success && known.message == unknown.message)
        check(known.message.contains("If that account exists"))
        check((notifier as CapturingPasswordResetNotifier).codes.containsKey(user.id.toString()))
    }

    @Test
    fun `verify otp mints a token and reset password rotates the credential`() {
        // A session that must be invalidated by the reset.
        val loginBody = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.phoneNumber + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val session = objectMapper.readValue(loginBody, AuthResponse::class.java)

        forgot(user.phoneNumber!!)
        val otp = capturedOtp()
        val wrong = verify(user.phoneNumber!!, "000000".takeIf { it != otp } ?: "111111")
        check(!wrong.success)
        val verified = verify(user.phoneNumber!!, otp)
        check(verified.success && !verified.resetToken.isNullOrBlank())

        check(!reset(verified.resetToken!!, "short").success)
        val done = reset(verified.resetToken!!, "newpassword1")
        check(done.success && done.message == "Password reset. You can now sign in.")
        // The token is single use.
        check(!reset(verified.resetToken!!, "anotherpass1").success)

        check(login(user.phoneNumber!!, "password123") == 401)
        check(login(user.phoneNumber!!, "newpassword1") == 200)
        mockMvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + session.refreshToken + "\"}")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `registration is rejected while the school gate is closed`() {
        settingsRepository.saveAndFlush(SchoolSystemSettingsEntity().apply {
            schoolId = school.id
            registrationOpen = false
        })
        val student = "{\"name\":\"New Learner\",\"phoneNumber\":\"0755600002\",\"password\":\"password123\",\"role\":\"STUDENT\",\"schoolId\":\"" + school.id + "\",\"schoolName\":\"" + school.name + "\"}"
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(student))
            .andExpect(status().isForbidden)
        val teacher = objectMapper.writeValueAsString(
            TeacherSignupRequest("New Teacher", "0755600003", "password123", schoolId = school.id.toString(), schoolName = school.name)
        )
        mockMvc.perform(post("/auth/signup/teacher").contentType(MediaType.APPLICATION_JSON).content(teacher))
            .andExpect(status().isForbidden)

        val settings = settingsRepository.findById(school.id).orElseThrow()
        settings.registrationOpen = true
        settingsRepository.saveAndFlush(settings)
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(student))
            .andExpect(status().isOk)
    }

    @Test
    fun `repeated failed logins lock the identifier with a retry hint`() {
        val phone = "0755600009"
        val lockUser = UserEntity()
        lockUser.phoneNumber = phone
        lockUser.email = "lock@auth.test"
        lockUser.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        lockUser.name = "Lock User"
        lockUser.role = Role.STUDENT
        lockUser.schoolId = school.id
        lockUser.isActive = true
        lockUser.isVerified = true
        userRepository.save(lockUser)

        repeat(5) { check(login(phone, "wrong-password") == 401) }
        mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + phone + "\",\"password\":\"wrong-password\"}")
        ).andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
    }
}
