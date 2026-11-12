package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.profile.entity.UserSettingsEntity
import com.afrithecus.brainbox.api.profile.repository.UserSettingsRepository
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Phase 3 rule matrix (P3.3): each guard is asserted independently against a decision table.
 * The real D1 policy is used with a stubbed subscription row, so the paid-channel gate is the
 * production rule rather than a second mock. No Spring context and no provider call.
 */
class AttendanceSmsPolicyTest {

    private val zone = "Africa/Nairobi"
    private val thursday: Instant = Instant.parse("2026-11-12T08:30:00Z") // 11:30 EAT
    private val saturday: Instant = Instant.parse("2026-11-14T08:30:00Z") // 11:30 EAT

    private val subscriptions: SubscriptionRepository = mock(SubscriptionRepository::class.java)
    private val tokens: DeviceTokenRepository = mock(DeviceTokenRepository::class.java)
    private val settings: UserSettingsRepository = mock(UserSettingsRepository::class.java)

    private val routing = AttendanceRecipientPolicy(subscriptions, Clock.fixed(thursday, ZoneOffset.UTC), 3)

    private val learner = UserEntity().apply {
        id = UUID.randomUUID()
        name = "Alice Mwangi"
        role = Role.STUDENT
        phoneNumber = "0712345678"
        schoolId = UUID.randomUUID()
        isActive = true
        accountKind = AccountKind.FULL
    }
    private val recipient = UserEntity().apply {
        id = UUID.randomUUID()
        name = "Guardian Mwangi"
        role = Role.PARENT
        phoneNumber = "0722000000"
        phoneVerifiedAt = Instant.now()
        isActive = true
        accountKind = AccountKind.FULL
    }
    private val optedIn = UserSettingsEntity().apply {
        userId = recipient.id
        attendanceSmsOptIn = true
    }
    private val activeSubscription = SubscriptionEntity().apply {
        userId = learner.id
        status = SubscriptionStatus.ACTIVE
        tier = SubscriptionTier.PRO
        expiryDate = thursday.plusSeconds(30L * 24 * 60 * 60)
    }

    private fun properties(
        enabled: Boolean = true,
        schoolDays: List<String> = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
        allowlist: List<String> = emptyList(),
        defaultOptIn: Boolean = false,
        requireVerified: Boolean = true,
        sendAfter: String = "10:30",
    ) = AttendanceSmsProperties(
        enabled = enabled,
        schoolDays = schoolDays,
        schoolAllowlist = allowlist,
        defaultOptIn = defaultOptIn,
        requireVerifiedPhone = requireVerified,
        sendAfter = sendAfter,
    )

    private fun policy(
        props: AttendanceSmsProperties = properties(),
        setting: UserSettingsEntity? = optedIn,
        subscription: SubscriptionEntity? = activeSubscription,
    ): AttendanceSmsPolicy {
        `when`(subscriptions.findByUserId(learner.id)).thenReturn(subscription)
        // Mockito's default for the boolean probe is already false (no working push token).
        `when`(settings.findByUserId(recipient.id)).thenReturn(setting)
        return AttendanceSmsPolicy(props, routing, tokens, settings, zone)
    }

    private fun record(status: AttendanceStatus = AttendanceStatus.ABSENT) = AttendanceRecordEntity().apply {
        id = UUID.randomUUID()
        classId = UUID.randomUUID()
        studentId = learner.id
        schoolId = learner.schoolId
        attendanceDate = java.time.LocalDate.ofInstant(thursday, java.time.ZoneId.of(zone))
        this.status = status
    }

    private fun decide(
        policy: AttendanceSmsPolicy = policy(),
        now: Instant = thursday,
        status: AttendanceStatus = AttendanceStatus.ABSENT,
    ) = policy.decide(learner, recipient, record(status), now)

    @Test
    fun `master switch off refuses every send`() {
        check(decide(policy(properties(enabled = false))).skipReason == AttendanceSmsPolicy.SkipReason.DISABLED)
    }

    @Test
    fun `school outside a configured allowlist is skipped`() {
        check(
            decide(policy(properties(allowlist = listOf(UUID.randomUUID().toString())))).skipReason ==
                AttendanceSmsPolicy.SkipReason.SCHOOL_NOT_ALLOWED,
        )
        check(decide(policy(properties(allowlist = listOf(learner.schoolId.toString())))).eligible)
    }

    @Test
    fun `only absent justifies a paid message`() {
        check(decide(status = AttendanceStatus.LATE).skipReason == AttendanceSmsPolicy.SkipReason.STATUS_NOT_ABSENT)
        check(decide(status = AttendanceStatus.PRESENT).skipReason == AttendanceSmsPolicy.SkipReason.STATUS_NOT_ABSENT)
    }

    @Test
    fun `a weekend is skipped by the school-day list`() {
        check(decide(now = saturday).skipReason == AttendanceSmsPolicy.SkipReason.NOT_SCHOOL_DAY)
        check(decide(policy(properties(schoolDays = listOf("SATURDAY"))), now = saturday).eligible)
    }

    @Test
    fun `before register close no message is sent so a correction can cancel it`() {
        val before = Instant.parse("2026-11-12T06:00:00Z") // 09:00 EAT
        check(decide(now = before).skipReason == AttendanceSmsPolicy.SkipReason.BEFORE_SEND_AFTER)
    }

    @Test
    fun `outside the nuisance window no message is sent`() {
        val evening = Instant.parse("2026-11-12T16:00:00Z") // 19:00 EAT
        check(decide(now = evening).skipReason == AttendanceSmsPolicy.SkipReason.OUTSIDE_WINDOW)
    }

    @Test
    fun `subscription ineligibility blocks the paid channel`() {
        check(decide(policy(subscription = null)).skipReason == AttendanceSmsPolicy.SkipReason.NOT_SUBSCRIBED)
        check(
            decide(
                policy(
                    subscription = SubscriptionEntity().apply {
                        userId = learner.id
                        status = SubscriptionStatus.EXPIRED
                        tier = SubscriptionTier.PRO
                        expiryDate = thursday.minusSeconds(30L * 24 * 60 * 60)
                    },
                ),
            ).skipReason == AttendanceSmsPolicy.SkipReason.NOT_SUBSCRIBED,
        )
    }

    @Test
    fun `invalid phone number is skipped`() {
        learner.phoneNumber = "not-a-phone"
        recipient.phoneNumber = "12345"
        check(decide().skipReason == AttendanceSmsPolicy.SkipReason.NO_PHONE)
    }

    @Test
    fun `unverified phone is skipped unless the requirement is relaxed`() {
        recipient.phoneVerifiedAt = null
        check(decide().skipReason == AttendanceSmsPolicy.SkipReason.UNVERIFIED_PHONE)
        check(decide(policy(properties(requireVerified = false))).eligible)
    }

    @Test
    fun `consent is required unless the default opts in`() {
        val noConsent = UserSettingsEntity().apply { userId = recipient.id; attendanceSmsOptIn = false }
        check(decide(policy(setting = noConsent)).skipReason == AttendanceSmsPolicy.SkipReason.NOT_OPTED_IN)
        check(decide(policy(setting = null)).skipReason == AttendanceSmsPolicy.SkipReason.NOT_OPTED_IN)
        check(decide(policy(properties(defaultOptIn = true), setting = null)).eligible)
    }

    @Test
    fun `a recipient with a working push token is skipped because push already covers it`() {
        val policy = policy()
        `when`(tokens.existsByUserId(recipient.id)).thenReturn(true)
        check(decide(policy).skipReason == AttendanceSmsPolicy.SkipReason.HAS_PUSH_TOKEN)
    }

    @Test
    fun `every gate passing yields the canonical phone`() {
        val decision = decide()
        check(decision.eligible)
        check(decision.phone == "0722000000")
        check(decision.skipReason == null)
    }

    @Test
    fun `message is one plain GSM-7 segment with the learner and date`() {
        val message = policy().message(learner, java.time.LocalDate.of(2026, 11, 12), "Alliance High")
        check(message.contains("Alice Mwangi"))
        check(message.contains("12 Nov"))
        check(message.length <= 160) { message }
        check(message.all { it.code in 32..126 })
    }
}
