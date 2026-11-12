package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * D1 routing matrix (docs/sms_and_attendance_notifications_plan.md §5). Pure decision
 * table: no Spring context, no delivery side effects.
 */
class AttendanceRecipientPolicyTest {

    private val now: Instant = Instant.parse("2026-11-12T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository: SubscriptionRepository = mock(SubscriptionRepository::class.java)
    private val policy = AttendanceRecipientPolicy(repository, clock, 3)

    @Test
    fun `active paid subscriber gets in-app, push and sms`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.ACTIVE, SubscriptionTier.PRO, now.plus(Duration.ofDays(30)))
        val decision = policy.decide(learner, learner)
        check(decision.inApp && decision.push && decision.sms)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.ACTIVE_SUBSCRIBER)
        check(decision.metadata["smsEligible"] == "true")
    }

    @Test
    fun `expired subscriber inside the grace window keeps the paid channel`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.EXPIRED, SubscriptionTier.PRO, now.minus(Duration.ofDays(2)))
        val decision = policy.decide(learner, learner)
        check(decision.sms)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.EXPIRED_WITHIN_GRACE)
    }

    @Test
    fun `expired beyond grace falls back to free channels`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.EXPIRED, SubscriptionTier.PRO, now.minus(Duration.ofDays(10)))
        val decision = policy.decide(learner, learner)
        check(decision.inApp && decision.push && !decision.sms)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.FREE_CHANNELS_ONLY)
        check(decision.metadata["smsEligible"] == "false")
    }

    @Test
    fun `grace boundary is exclusive so exactly grace days old is no longer paid`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.EXPIRED, SubscriptionTier.PRO, now.minus(Duration.ofDays(3)))
        check(!policy.decide(learner, learner).sms)
    }

    @Test
    fun `a stale ACTIVE row with a past expiry is treated as lapsed`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.ACTIVE, SubscriptionTier.PRO, now.minus(Duration.ofDays(10)))
        val decision = policy.decide(learner, learner)
        check(!decision.sms)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.FREE_CHANNELS_ONLY)
    }

    @Test
    fun `BASE tier and NONE status never get the paid channel`() {
        val base = learner()
        stub(base, SubscriptionStatus.ACTIVE, SubscriptionTier.BASE, now.plus(Duration.ofDays(30)))
        check(!policy.decide(base, base).sms)

        val none = learner()
        stub(none, SubscriptionStatus.NONE, SubscriptionTier.PRO, now.plus(Duration.ofDays(30)))
        check(!policy.decide(none, none).sms)
    }

    @Test
    fun `no subscription row means free channels only`() {
        val learner = learner()
        val decision = policy.decide(learner, learner)
        check(decision.inApp && decision.push && !decision.sms)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.FREE_CHANNELS_ONLY)
    }

    @Test
    fun `deactivated recipient gets nothing`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.ACTIVE, SubscriptionTier.PRO, now.plus(Duration.ofDays(30)))
        val parent = user(Role.PARENT).apply { isActive = false }
        val decision = policy.decide(learner, parent)
        check(!decision.deliverable)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.RECIPIENT_DEACTIVATED)
    }

    @Test
    fun `roster-only recipient gets nothing`() {
        val learner = learner()
        val rosterOnly = user(Role.STUDENT).apply { accountKind = AccountKind.ROSTER_ONLY }
        check(!policy.decide(learner, rosterOnly).deliverable)
    }

    @Test
    fun `deactivated learner silences every recipient including an active parent`() {
        val learner = learner().apply { isActive = false }
        val parent = user(Role.PARENT)
        stub(learner, SubscriptionStatus.ACTIVE, SubscriptionTier.PRO, now.plus(Duration.ofDays(30)))
        val decision = policy.decide(learner, parent)
        check(!decision.deliverable)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.LEARNER_DEACTIVATED)
    }

    @Test
    fun `legacy parent alias inherits the learner's paid eligibility`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.ACTIVE, SubscriptionTier.PRO, now.plus(Duration.ofDays(30)))
        val parent = user(Role.PARENT)
        val decision = policy.decide(learner, parent)
        check(decision.inApp && decision.push && decision.sms)
        check(decision.reason == AttendanceRecipientPolicy.RoutingReason.ACTIVE_SUBSCRIBER)
    }

    @Test
    fun `the PARENT role never grants the paid channel on its own`() {
        val learner = learner()
        stub(learner, SubscriptionStatus.ACTIVE, SubscriptionTier.BASE, now.plus(Duration.ofDays(30)))
        val parent = user(Role.PARENT)
        check(!policy.decide(learner, parent).sms)
    }

    private fun learner(): UserEntity = user(Role.STUDENT)

    private fun user(role: Role): UserEntity = UserEntity().apply {
        this.role = role
        name = "Routing Test"
        isActive = true
        accountKind = AccountKind.FULL
    }

    private fun stub(
        learner: UserEntity,
        status: SubscriptionStatus,
        tier: SubscriptionTier,
        expiry: Instant,
    ) {
        val subscription = SubscriptionEntity().apply {
            userId = learner.id
            this.status = status
            this.tier = tier
            expiryDate = expiry
        }
        `when`(repository.findByUserId(learner.id)).thenReturn(subscription)
    }
}
