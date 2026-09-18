package com.afrithecus.brainbox.api.payments

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.payments.repository.PaymentTransactionRepository
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionHistoryRepository
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * IntaSend/M-Pesa relay end to end with a fake gateway (no network): price and
 * phone validation, STK push, the tri-state query, an idempotent COMPLETE
 * callback that activates the subscription once, the Explorer-to-Pro upgrade
 * that preserves expiry, and the challenge check on the public webhook.
 */
@SpringBootTest(
    properties = [
        "app.payments.enabled=true",
        "app.payments.sandbox=true",
        "app.payments.callback-challenge=test-challenge",
        "app.payments.intasend.secret-key=test-secret",
        "app.payments.intasend.publishable-key=test-publishable",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(PaymentsWebTests.FakeGatewayConfig::class)
class PaymentsWebTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val gateway: PaymentGateway,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val schoolRepository: SchoolRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val transactions: PaymentTransactionRepository,
    @Autowired private val subscriptions: SubscriptionRepository,
    @Autowired private val history: SubscriptionHistoryRepository,
) {

    @TestConfiguration
    class FakeGatewayConfig {
        @Bean
        @Primary
        fun fakePaymentGateway(): PaymentGateway = FakePaymentGateway()
    }

    class FakePaymentGateway : PaymentGateway {
        override val name: String = "FAKE"
        val pushes = mutableListOf<StkPushCommand>()
        var outcome: StkPushOutcome = StkPushOutcome.Accepted("INV-TEST-1", "PENDING")
        var state: GatewayPaymentState = GatewayPaymentState("PENDING")

        override fun stkPush(command: StkPushCommand): StkPushOutcome {
            pushes += command
            return outcome
        }

        override fun status(providerReference: String): GatewayPaymentState = state
    }

    private val fake: FakePaymentGateway get() = gateway as FakePaymentGateway
    private lateinit var student: UserEntity
    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        fake.pushes.clear()
        fake.outcome = StkPushOutcome.Accepted("INV-TEST-1", "PENDING")
        fake.state = GatewayPaymentState("PENDING")
        val school = schoolRepository.save(SchoolEntity().apply { name = "Payments Test School"; isActive = true })
        student = userRepository.save(
            UserEntity().apply {
                phoneNumber = "0700111222"
                email = "payer@payments.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                name = "Payer Student"
                role = Role.STUDENT
                schoolId = school.id
                gradeLevel = "Grade 4"
                isVerified = true
                isActive = true
            }
        )
        val login = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"payer@payments.test\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        token = objectMapper.readValue(login, AuthResponse::class.java).sessionToken!!
    }

    @Test
    fun rejectsMismatchedAmountsAndBadPhoneNumbers() {
        mockMvc.perform(stkPushForm(amount = 150, phone = "0712345678", tier = "EXPLORER"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(stkPushForm(amount = 100, phone = "12345", tier = "EXPLORER"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(stkPushForm(amount = 50, phone = "0712345678", tier = "PRO"))
            .andExpect(status().isBadRequest) // upgrade price with no Explorer subscription
        check(fake.pushes.isEmpty()) { "an invalid request must not reach the gateway" }
    }

    @Test
    fun stkPushNormalisesThePhoneAndReturnsATransactionId() {
        val body = mockMvc.perform(stkPushForm(amount = 100, phone = "0712345678", tier = "EXPLORER"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val response = objectMapper.readValue(body, com.afrithecus.brainbox.api.payments.web.StkPushResponse::class.java)
        check(response.success)
        check(response.transactionId != null)
        check(response.subscription == null)

        check(fake.pushes.size == 1)
        val push = fake.pushes.single()
        check(push.phoneNumber == "254712345678") { "phone must be normalised, was " + push.phoneNumber }
        check(push.amount == 100)
        check(push.reference == response.transactionId)

        val row = transactions.findById(UUID.fromString(response.transactionId!!)).orElseThrow()
        check(row.status == PaymentStatus.PENDING.name)
        check(row.providerRef == "INV-TEST-1")
    }

    @Test
    fun aCompleteCallbackActivatesOnceAndReplaysAreIdempotent() {
        val pushBody = mockMvc.perform(stkPushForm(amount = 100, phone = "0712345678", tier = "EXPLORER"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val transactionId = objectMapper.readValue(pushBody, com.afrithecus.brainbox.api.payments.web.StkPushResponse::class.java).transactionId!!

        // A forged challenge is rejected.
        mockMvc.perform(callback("{\"invoice_id\":\"INV-TEST-1\",\"state\":\"COMPLETE\",\"challenge\":\"wrong\"}"))
            .andExpect(status().isForbidden)

        mockMvc.perform(callback("{\"invoice_id\":\"INV-TEST-1\",\"state\":\"COMPLETE\",\"challenge\":\"test-challenge\"}"))
            .andExpect(status().isOk)

        val afterFirst = subscriptions.findByUserId(student.id)!!
        check(afterFirst.tier == SubscriptionTier.EXPLORER)
        check(afterFirst.status == SubscriptionStatus.ACTIVE)
        check(afterFirst.totalPaid == 100)
        check(afterFirst.mpesaTransactionId == "INV-TEST-1")
        val firstExpiry = afterFirst.expiryDate!!
        check(firstExpiry.isAfter(Instant.now().plus(Duration.ofDays(28)))) { "a fresh month must be granted" }
        check(history.findAllByUserIdOrderByCreatedAtDesc(student.id).single().action == "CREATED")

        // Replaying the same confirmation must not extend or double-charge.
        mockMvc.perform(callback("{\"invoice_id\":\"INV-TEST-1\",\"state\":\"COMPLETE\",\"challenge\":\"test-challenge\"}"))
            .andExpect(status().isOk)
        val afterReplay = subscriptions.findByUserId(student.id)!!
        check(afterReplay.expiryDate == firstExpiry) { "replay must not extend expiry" }
        check(afterReplay.totalPaid == 100) { "replay must not double-charge" }
        check(history.findAllByUserIdOrderByCreatedAtDesc(student.id).size == 1)

        // The learner now reads an active subscription.
        val subscriptionJson = mockMvc.perform(
            get("/subscriptions/" + student.id).header("Authorization", "Bearer " + token)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        check(subscriptionJson.contains("\"ACTIVE\""))
        check(transactionId.isNotEmpty())
    }

    @Test
    fun queryPollsTheProviderWhenTheWebhookIsDelayed() {
        val pushBody = mockMvc.perform(stkPushForm(amount = 150, phone = "254712345678", tier = "PRO"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val transactionId = objectMapper.readValue(pushBody, com.afrithecus.brainbox.api.payments.web.StkPushResponse::class.java).transactionId!!

        // Still pending: success with no subscription.
        var body = mockMvc.perform(queryForm(transactionId)).andExpect(status().isOk).andReturn().response.contentAsString
        var response = objectMapper.readValue(body, com.afrithecus.brainbox.api.payments.web.PaymentQueryResponse::class.java)
        check(response.success)
        check(response.subscription == null)

        // The provider now reports COMPLETE: the query resolves and activates.
        fake.state = GatewayPaymentState("COMPLETE")
        body = mockMvc.perform(queryForm(transactionId)).andExpect(status().isOk).andReturn().response.contentAsString
        response = objectMapper.readValue(body, com.afrithecus.brainbox.api.payments.web.PaymentQueryResponse::class.java)
        check(response.success)
        check(response.subscription != null)
        check(response.subscription!!.tier == "PRO")
        check(subscriptions.findByUserId(student.id)!!.totalPaid == 150)
    }

    @Test
    fun aFiftyShillingUpgradePreservesTheExplorerExpiry() {
        val expiry = Instant.now().plus(Duration.ofDays(12))
        subscriptions.save(
            (subscriptions.findByUserId(student.id) ?: SubscriptionEntity().apply { userId = student.id }).apply {
                tier = SubscriptionTier.EXPLORER
                status = SubscriptionStatus.ACTIVE
                expiryDate = expiry
                totalPaid = 100
                mpesaTransactionId = "INV-OLD"
            }
        )
        // The session's student must be reloaded in this transaction.
        val pushBody = mockMvc.perform(stkPushForm(amount = 50, phone = "0712345678", tier = "PRO"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        check(
            objectMapper.readValue(pushBody, com.afrithecus.brainbox.api.payments.web.StkPushResponse::class.java).success,
        )
        mockMvc.perform(callback("{\"invoice_id\":\"INV-TEST-1\",\"state\":\"COMPLETE\",\"challenge\":\"test-challenge\"}"))
            .andExpect(status().isOk)

        val upgraded = subscriptions.findByUserId(student.id)!!
        check(upgraded.tier == SubscriptionTier.PRO)
        check(upgraded.status == SubscriptionStatus.ACTIVE)
        check(upgraded.expiryDate == expiry) { "an upgrade must preserve the Explorer expiry" }
        check(upgraded.totalPaid == 150)
        check(history.findAllByUserIdOrderByCreatedAtDesc(student.id).single().action == "UPGRADED")
    }

    @Test
    fun aFailedCallbackMarksThePaymentFailed() {
        val pushBody = mockMvc.perform(stkPushForm(amount = 100, phone = "0712345678", tier = "EXPLORER"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val transactionId = objectMapper.readValue(pushBody, com.afrithecus.brainbox.api.payments.web.StkPushResponse::class.java).transactionId!!

        mockMvc.perform(
            callback("{\"invoice_id\":\"INV-TEST-1\",\"state\":\"FAILED\",\"failed_reason\":\"Insufficient funds\",\"challenge\":\"test-challenge\"}")
        ).andExpect(status().isOk)

        val body = mockMvc.perform(queryForm(transactionId)).andExpect(status().isOk).andReturn().response.contentAsString
        val response = objectMapper.readValue(body, com.afrithecus.brainbox.api.payments.web.PaymentQueryResponse::class.java)
        check(!response.success)
        check(response.message!!.contains("Insufficient funds"))
        val subscription = subscriptions.findByUserId(student.id)
        check(subscription == null || subscription.status != SubscriptionStatus.ACTIVE) {
            "a failed payment must not activate a subscription"
        }
    }

    // ------------------------------------------------------------- fixtures

    private fun stkPushForm(amount: Int, phone: String, tier: String) = post("/payments/stk-push")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("userId", student.id.toString())
        .param("amount", amount.toString())
        .param("phoneNumber", phone)
        .param("tier", tier)

    private fun queryForm(transactionId: String) = post("/payments/query")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("transactionId", transactionId)

    private fun callback(json: String) = post("/payments/mpesa/callback")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json)
}
