package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.attendance.entity.AttendanceRecordEntity
import com.afrithecus.brainbox.api.attendance.model.AttendanceStatus
import com.afrithecus.brainbox.api.attendance.repository.AttendanceRecordRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.notification.entity.NotificationEntity
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import com.afrithecus.brainbox.api.profile.entity.UserSettingsEntity
import com.afrithecus.brainbox.api.profile.repository.UserSettingsRepository
import com.afrithecus.brainbox.api.push.repository.DeviceTokenRepository
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Phase 3/4 rule matrix (P3.3, P4.1, P4.3, P4.6): each guard is asserted independently against a
 * decision table. The real D1 policy is used with a stubbed subscription row, so the paid-channel
 * gate is the production rule rather than a second mock. No Spring context and no provider call.
 */
class AttendanceSmsPolicyTest {

    private val zone = "Africa/Nairobi"
    private val zoneId = ZoneId.of(zone)
    private val thursday: Instant = Instant.parse("2026-11-12T08:30:00Z") // 11:30 EAT
    private val saturday: Instant = Instant.parse("2026-11-14T08:30:00Z") // 11:30 EAT
    private val day: LocalDate = LocalDate.ofInstant(thursday, zoneId)
    private val notifFrom: Instant = day.atStartOfDay(zoneId).toInstant()
    private val notifTo: Instant = day.plusDays(1).atStartOfDay(zoneId).toInstant()

    private val subscriptions: SubscriptionRepository = mock(SubscriptionRepository::class.java)
    private val tokens: DeviceTokenRepository = mock(DeviceTokenRepository::class.java)
    private val settings: UserSettingsRepository = mock(UserSettingsRepository::class.java)
    private val notifications: NotificationRepository = mock(NotificationRepository::class.java)
    private val records: AttendanceRecordRepository = mock(AttendanceRecordRepository::class.java)
    private val mapper = ObjectMapper()

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

    @BeforeEach
    fun setUp() {
        `when`(settings.findByUserId(recipient.id)).thenReturn(optedIn)
        `when`(subscriptions.findByUserId(learner.id)).thenReturn(activeSubscription)
        `when`(notifications.findAllByUserIdAndTypeAndCreatedAtBetweenOrderByCreatedAtAsc(recipient.id, NotificationType.ATTENDANCE, notifFrom, notifTo))
            .thenReturn(emptyList())
        `when`(records.findAllByStudentIdOrderByAttendanceDateDesc(learner.id)).thenReturn(emptyList())
    }

    private fun properties(
        enabled: Boolean = true,
        schoolDays: List<String> = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
        allowlist: List<String> = emptyList(),
        defaultOptIn: Boolean = false,
        requireVerified: Boolean = true,
        sendAfter: String = "10:30",
        readAckEnabled: Boolean = true,
        readAckMinutes: Long = 20,
        digestEnabled: Boolean = true,
        riskThresholdEnabled: Boolean = false,
        riskMinConsecutiveAbsences: Int = 2,
        riskTermAttendancePercent: Double = 90.0,
        language: String = "EN",
    ) = AttendanceSmsProperties(
        enabled = enabled,
        schoolDays = schoolDays,
        schoolAllowlist = allowlist,
        defaultOptIn = defaultOptIn,
        requireVerifiedPhone = requireVerified,
        sendAfter = sendAfter,
        readAckEnabled = readAckEnabled,
        readAckMinutes = readAckMinutes,
        digestEnabled = digestEnabled,
        riskThresholdEnabled = riskThresholdEnabled,
        riskMinConsecutiveAbsences = riskMinConsecutiveAbsences,
        riskTermAttendancePercent = riskTermAttendancePercent,
        language = language,
    )

    private fun policy(
        props: AttendanceSmsProperties = properties(),
        setting: UserSettingsEntity? = optedIn,
        subscription: SubscriptionEntity? = activeSubscription,
    ): AttendanceSmsPolicy {
        `when`(subscriptions.findByUserId(learner.id)).thenReturn(subscription)
        // Mockito's default for the boolean probe is already false (no working push token).
        `when`(settings.findByUserId(recipient.id)).thenReturn(setting)
        return AttendanceSmsPolicy(props, routing, tokens, settings, notifications, records, mapper, zone)
    }

    private fun record(status: AttendanceStatus = AttendanceStatus.ABSENT) = AttendanceRecordEntity().apply {
        id = UUID.randomUUID()
        classId = UUID.randomUUID()
        studentId = learner.id
        schoolId = learner.schoolId
        attendanceDate = day
        this.status = status
    }

    private fun historyRecord(status: AttendanceStatus, daysAgo: Long) = record(status).apply {
        attendanceDate = day.minusDays(daysAgo)
    }

    private fun pushToken() {
        `when`(tokens.existsByUserId(recipient.id)).thenReturn(true)
    }

    private fun notification(read: Boolean = false, createdAt: Instant = thursday.minusSeconds(3600)) =
        NotificationEntity().apply {
            userId = recipient.id
            title = "Absence Alert"
            message = "Absence notification"
            type = NotificationType.ATTENDANCE
            this.createdAt = createdAt
            isRead = read
            metadata = "{\"studentId\":\"" + learner.id + "\"}"
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

    // ------------------------------------------------------------------ P4.1 read-ack

    @Test
    fun `read-ack disabled restores the phase 3 rule for every token holder`() {
        pushToken()
        check(
            decide(policy(properties(readAckEnabled = false))).skipReason ==
                AttendanceSmsPolicy.SkipReason.HAS_PUSH_TOKEN,
        )
    }

    @Test
    fun `token holder SMS waits for the unread ack window and then fires`() {
        pushToken()
        `when`(notifications.findAllByUserIdAndTypeAndCreatedAtBetweenOrderByCreatedAtAsc(recipient.id, NotificationType.ATTENDANCE, notifFrom, notifTo))
            .thenReturn(listOf(notification(read = false, createdAt = thursday.minusSeconds(300))))
        check(decide().skipReason == AttendanceSmsPolicy.SkipReason.AWAITING_READ_ACK)

        `when`(notifications.findAllByUserIdAndTypeAndCreatedAtBetweenOrderByCreatedAtAsc(recipient.id, NotificationType.ATTENDANCE, notifFrom, notifTo))
            .thenReturn(listOf(notification(read = false, createdAt = thursday.minusSeconds(3600))))
        check(decide().eligible)
    }

    @Test
    fun `a read push cancels the SMS`() {
        pushToken()
        `when`(notifications.findAllByUserIdAndTypeAndCreatedAtBetweenOrderByCreatedAtAsc(recipient.id, NotificationType.ATTENDANCE, notifFrom, notifTo))
            .thenReturn(listOf(notification(read = true, createdAt = thursday.minusSeconds(3600))))
        check(decide().skipReason == AttendanceSmsPolicy.SkipReason.PUSH_ALREADY_READ)
    }

    @Test
    fun `a token holder with no push attempt falls back immediately`() {
        pushToken()
        check(decide().eligible)
    }

    // ------------------------------------------------------------------ P4.3 risk threshold

    @Test
    fun `risk threshold keeps a first isolated absence on the free channel`() {
        pushToken()
        `when`(records.findAllByStudentIdOrderByAttendanceDateDesc(learner.id))
            .thenReturn(listOf(historyRecord(AttendanceStatus.PRESENT, 1), historyRecord(AttendanceStatus.PRESENT, 2)))
        val policy = policy(properties(riskThresholdEnabled = true))
        check(decide(policy).skipReason == AttendanceSmsPolicy.SkipReason.BELOW_RISK_THRESHOLD)
    }

    @Test
    fun `risk threshold lets a second consecutive absence through`() {
        pushToken()
        `when`(records.findAllByStudentIdOrderByAttendanceDateDesc(learner.id))
            .thenReturn(listOf(historyRecord(AttendanceStatus.ABSENT, 1), historyRecord(AttendanceStatus.PRESENT, 2)))
        check(decide(policy(properties(riskThresholdEnabled = true))).eligible)
    }

    @Test
    fun `risk threshold also passes a learner below the term attendance line`() {
        pushToken()
        `when`(records.findAllByStudentIdOrderByAttendanceDateDesc(learner.id))
            .thenReturn(
                listOf(
                    historyRecord(AttendanceStatus.PRESENT, 1),
                    historyRecord(AttendanceStatus.ABSENT, 2),
                    historyRecord(AttendanceStatus.ABSENT, 3),
                    historyRecord(AttendanceStatus.ABSENT, 4),
                    historyRecord(AttendanceStatus.ABSENT, 5),
                ),
            )
        check(decide(policy(properties(riskThresholdEnabled = true, riskTermAttendancePercent = 90.0))).eligible)
    }

    // ------------------------------------------------------------------ P4.6 templates

    @Test
    fun `every gate passing yields the canonical phone`() {
        val decision = decide()
        check(decision.eligible)
        check(decision.phone == "0722000000")
        check(decision.skipReason == null)
    }

    @Test
    fun `message is one plain GSM-7 segment with the learner and date`() {
        val message = policy().message(learner, LocalDate.of(2026, 11, 12), "Alliance High")
        check(message.contains("Alice Mwangi"))
        check(message.contains("12 Nov"))
        check(message.length <= 160) { message }
        check(message.all { it.code in 32..126 })
    }

    @Test
    fun `Swahili template pair is selectable and stays one ASCII segment`() {
        val sw = policy(properties(language = "SW"))
        val message = sw.message(learner, LocalDate.of(2026, 11, 12), "Alliance High")
        check(message.contains("hakuwepo")) { message }
        check(message.all { it.code in 32..126 })
        check(message.length <= 160)
    }

    @Test
    fun `digest lists every child of the family in one segment`() {
        val digest = policy().digestMessage(listOf("Alice Mwangi", "Brian Otieno"), LocalDate.of(2026, 11, 12), "Alliance High")
        check(digest.contains("Alice Mwangi"))
        check(digest.contains("Brian Otieno"))
        check(digest.length <= 160) { digest }
        check(digest.all { it.code in 32..126 })

        val sw = policy(properties(language = "SW"))
            .digestMessage(listOf("Alice Mwangi", "Brian Otieno"), LocalDate.of(2026, 11, 12), "Alliance High")
        check(sw.contains("hawakuwepo")) { sw }
    }

    @Test
    fun `digest truncates long name lists rather than billing a second segment`() {
        val many = (1..20).map { "Learner Number " + it + " Mwangi" }
        val digest = policy().digestMessage(many, LocalDate.of(2026, 11, 12), "Alliance High")
        check(digest.length <= 160) { digest }
        check(digest.contains("more")) { digest }
    }
}
