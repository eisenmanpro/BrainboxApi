package com.afrithecus.brainbox.api.payments

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.payments.repository.PaymentTransactionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * A deployment with payments off must fail closed: the request is accepted by the
 * endpoint, recorded, and answered as unavailable, but no gateway is called and
 * no subscription is activated.
 */
@SpringBootTest(properties = ["app.payments.enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PaymentsDisabledTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val gateway: PaymentGateway,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val transactions: PaymentTransactionRepository,
) {

    private lateinit var student: UserEntity
    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        val school = schoolRepository.save(SchoolEntity().apply { name = "Disabled Payments School"; isActive = true })
        student = userRepository.save(
            UserEntity().apply {
                phoneNumber = "0700333444"
                email = "disabled@payments.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                name = "Disabled Payer"
                role = Role.STUDENT
                schoolId = school.id
                gradeLevel = "Grade 4"
                isVerified = true
                isActive = true
            }
        )
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"disabled@payments.test\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        token = objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    @Test
    fun aDisabledDeploymentAnswersUnavailableAndNeverCallsTheGateway() {
        check(gateway is DisabledPaymentGateway) { "the disabled gateway must be wired when payments are off" }
        val body = mockMvc.perform(
            post("/payments/stk-push")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("userId", student.id.toString())
                .param("amount", "100")
                .param("phoneNumber", "0712345678")
                .param("tier", "EXPLORER")
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val response = objectMapper.readValue(body, com.afrithecus.brainbox.api.payments.web.StkPushResponse::class.java)
        check(!response.success)
        check(response.message.contains("unavailable"))
        val row = transactions.findById(UUID.fromString(response.transactionId!!)).orElseThrow()
        check(row.status == PaymentStatus.FAILED.name)
    }
}
