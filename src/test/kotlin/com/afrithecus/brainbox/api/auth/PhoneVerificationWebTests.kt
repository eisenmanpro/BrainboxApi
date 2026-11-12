package com.afrithecus.brainbox.api.auth

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.auth.web.PhoneVerificationMessagePayload
import com.afrithecus.brainbox.api.auth.web.PhoneVerificationStatusPayload
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.PhoneVerificationChallengeRepository
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone-verification OTP flow (docs/phone_verification.md): request, confirm, attempt cap,
 * expiry, resend throttle, provider failure and the /auth/me signal. The provider is a capturing
 * notifier so no SMS is sent.
 */
@SpringBootTest(
    properties = [
        "app.auth.phone-verification.enabled=true",
        // Keep the challenge attempt cap below the account throttle's 5 failures so the cap
        // itself (not the throttle) is what the test observes.
        "app.auth.phone-verification.max-attempts=3",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PhoneVerificationWebTests.NotifierConfig::class)
class PhoneVerificationWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val challengeRepository: PhoneVerificationChallengeRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val notifier: PhoneVerificationNotifier,
) {

    private lateinit var school: SchoolEntity
    private lateinit var user: UserEntity
    private lateinit var token: String

    @TestConfiguration
    class NotifierConfig {
        @Bean
        @Primary
        fun phoneVerificationNotifier(): PhoneVerificationNotifier = CapturingPhoneVerificationNotifier()
    }

    class CapturingPhoneVerificationNotifier : PhoneVerificationNotifier {
        val codes = ConcurrentHashMap<String, String>()
        @Volatile var failNext = false

        override fun send(user: UserEntity, code: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("provider is down")
            }
            codes[user.id.toString()] = code
        }
    }

    private fun capturedCode(): String = (notifier as CapturingPhoneVerificationNotifier).codes[user.id.toString()]!!

    @BeforeEach
    fun setUp() {
        school = schoolRepository.save(SchoolEntity().apply { name = "Alliance High School"; isActive = true })
        val entity = UserEntity()
        entity.phoneNumber = "0755600201"
        entity.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        entity.name = "Phone User"
        entity.role = Role.STUDENT
        entity.schoolId = school.id
        entity.isActive = true
        entity.isVerified = true
        user = userRepository.save(entity)
        token = login("0755600201", "password123")
    }

    private fun login(identifier: String, password: String): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, AuthResponse::class.java).sessionToken!!
    }

    private fun fetchStatus(): PhoneVerificationStatusPayload {
        val body = mockMvc.perform(
            get("/auth/phone/verification").header("Authorization", "Bearer " + token)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, PhoneVerificationStatusPayload::class.java)
    }

    private fun requestCode(): PhoneVerificationMessagePayload {
        val body = mockMvc.perform(
            post("/auth/phone/verification/request").header("Authorization", "Bearer " + token)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, PhoneVerificationMessagePayload::class.java)
    }

    private fun confirmRaw(code: String): org.springframework.test.web.servlet.ResultActions =
        mockMvc.perform(
            post("/auth/phone/verification/confirm").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
        )

    private fun confirm(code: String): PhoneVerificationMessagePayload {
        val body = confirmRaw(code).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, PhoneVerificationMessagePayload::class.java)
    }

    private fun verifyPhone() {
        requestCode()
        confirm(capturedCode())
    }

    @Test
    fun `status is enabled and unverified before the flow`() {
        val payload = fetchStatus()
        check(payload.enabled)
        check(!payload.verified)
        check(payload.canRequest)
        check(payload.phoneNumber == "0755600201")
        check(payload.resendAfterSeconds == 0L)
    }

    @Test
    fun `request sends a code and confirm verifies the phone`() {
        val sent = requestCode()
        check(sent.success)
        check(sent.message.contains("0755"))
        check(!sent.message.contains(capturedCode()))
        check(sent.resendAfterSeconds == 60L)

        // A wrong code does not verify.
        confirmRaw("999999").andExpect(status().isBadRequest)
        val wrong = if (capturedCode() == "999999") "000000" else "999999"
        confirmRaw(wrong).andExpect(status().isBadRequest)
        check(userRepository.findById(user.id).orElseThrow().phoneVerifiedAt == null)

        val done = confirm(capturedCode())
        check(done.success)
        check(userRepository.findById(user.id).orElseThrow().phoneVerifiedAt != null)

        // /auth/me carries the signal.
        val me = mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.user.phoneVerified").value(true))
            .andReturn()
        check(me.response.contentAsString.contains("phoneVerified"))

        val after = fetchStatus()
        check(after.verified)
        check(!after.canRequest)
    }

    @Test
    fun `a verified account cannot request another code`() {
        verifyPhone()
        mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict)
    }

    @Test
    fun `an expired code is rejected`() {
        requestCode()
        val challenge = challengeRepository.findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(user.id)!!
        challenge.expiresAt = java.time.Instant.now().minus(Duration.ofMinutes(1))
        challengeRepository.saveAndFlush(challenge)
        confirmRaw(capturedCode()).andExpect(status().isBadRequest)
        check(userRepository.findById(user.id).orElseThrow().phoneVerifiedAt == null)
    }

    @Test
    fun `too many wrong attempts consume the code`() {
        requestCode()
        val real = capturedCode()
        val wrong = if (real == "000000") "111111" else "000000"
        // max-attempts=3: the fourth failure crosses the cap and consumes the challenge.
        repeat(3) { confirmRaw(wrong).andExpect(status().isBadRequest) }
        confirmRaw(wrong)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Too many incorrect attempts. Request a new code."))
        // Even the real code can no longer be used.
        confirmRaw(real).andExpect(status().isBadRequest)
    }

    @Test
    fun `a second request is throttled by the resend cooldown`() {
        requestCode()
        mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + token))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.details.retryAfter").exists())
    }

    @Test
    fun `an account without a phone number cannot request a code`() {
        val noPhone = UserEntity()
        noPhone.phoneNumber = null
        noPhone.email = "no-phone-" + UUID.randomUUID() + "@auth.test"
        noPhone.passwordHash = passwordEncoder.encode("password123") ?: error("encode")
        noPhone.name = "No Phone"
        noPhone.role = Role.STUDENT
        noPhone.schoolId = school.id
        noPhone.isActive = true
        noPhone.isVerified = true
        userRepository.save(noPhone)
        val noPhoneToken = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + noPhone.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
            .let { objectMapper.readValue(it, AuthResponse::class.java).sessionToken!! }
        mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + noPhoneToken))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `a provider failure is reported honestly`() {
        (notifier as CapturingPhoneVerificationNotifier).failNext = true
        mockMvc.perform(post("/auth/phone/verification/request").header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `the endpoints require a session`() {
        mockMvc.perform(get("/auth/phone/verification")).andExpect(status().isUnauthorized)
    }
}
