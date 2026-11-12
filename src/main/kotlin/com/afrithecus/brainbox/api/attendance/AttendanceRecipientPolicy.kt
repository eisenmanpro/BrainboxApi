package com.afrithecus.brainbox.api.attendance

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.AccountKind
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.subscription.entity.SubscriptionEntity
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * D1 subscription-aware routing for the attendance fan-out
 * (docs/sms_and_attendance_notifications_plan.md §5, owner decision 2026-11-12).
 *
 * Free channels (in-app row + FCM push) reach every non-deactivated FULL account,
 * including explorer/expired families: the attendance safety net must not depend on a
 * payment having cleared. The paid channel (attendance SMS, Phase 3) additionally needs
 * the learner's subscription to be a non-BASE tier whose expiry is no older than
 * [graceDays], so a lapsed renewal does not cut the family off on the same day.
 *
 * A legacy PARENT row is an alias, never the payer: its eligibility is computed from the
 * learner it aliases (the learner argument), not from its own role. The recipient's own
 * deactivation or non-login (ROSTER_ONLY) state still blocks delivery.
 *
 * Nothing here sends anything. Phase 2 only decides, records the reason in the
 * notification metadata and suppresses the push when the account cannot receive it;
 * [com.afrithecus.brainbox.api.notification.NotificationService] still owns delivery.
 */
@Component
class AttendanceRecipientPolicy(
    private val subscriptionRepository: SubscriptionRepository,
    private val clock: Clock,
    @Value("\${app.attendance.routing-grace-days:3}") graceDaysRaw: Long,
) {

    private val graceDays: Long = graceDaysRaw.coerceAtLeast(0)

    /** Why a recipient is routed the way it is; surfaced as notification metadata. */
    enum class RoutingReason {
        /** Paid tier, ACTIVE and not expired: in-app + push + SMS (subject to Phase 3 gates). */
        ACTIVE_SUBSCRIBER,

        /** Paid tier whose expiry is within the grace window: still eligible for the paid channel. */
        EXPIRED_WITHIN_GRACE,

        /** Explorer/BASE, lapsed beyond grace, or no subscription row: free channels only. */
        FREE_CHANNELS_ONLY,

        /** The recipient account is deactivated: nothing is delivered. */
        RECIPIENT_DEACTIVATED,

        /** The recipient can never log in (ROSTER_ONLY): nothing is delivered. */
        RECIPIENT_ROSTER_ONLY,

        /** The learner the alert is about is deactivated: nobody is notified about them. */
        LEARNER_DEACTIVATED,

        /** The learner is a provisioned ROSTER_ONLY record: not an attendance subject. */
        LEARNER_ROSTER_ONLY,
    }

    data class Decision(
        val inApp: Boolean,
        val push: Boolean,
        val sms: Boolean,
        val reason: RoutingReason,
    ) {
        val deliverable: Boolean get() = inApp || push || sms

        /** Audit trail written into the in-app row's metadata (no PII, no phone numbers). */
        val metadata: Map<String, String>
            get() = mapOf(
                "routingReason" to reason.name,
                "smsEligible" to sms.toString(),
            )
    }

    fun decide(learner: UserEntity, recipient: UserEntity?): Decision {
        if (!learner.isActive) return denied(RoutingReason.LEARNER_DEACTIVATED)
        if (learner.accountKind != AccountKind.FULL) return denied(RoutingReason.LEARNER_ROSTER_ONLY)
        if (recipient == null || !recipient.isActive) return denied(RoutingReason.RECIPIENT_DEACTIVATED)
        if (recipient.accountKind != AccountKind.FULL) return denied(RoutingReason.RECIPIENT_ROSTER_ONLY)
        return when (val reason = paidEligibility(subscriptionRepository.findByUserId(learner.id), clock.instant())) {
            RoutingReason.ACTIVE_SUBSCRIBER, RoutingReason.EXPIRED_WITHIN_GRACE ->
                Decision(inApp = true, push = true, sms = true, reason = reason)
            else -> Decision(inApp = true, push = true, sms = false, reason = RoutingReason.FREE_CHANNELS_ONLY)
        }
    }

    /**
     * The learner's subscription determines the paid channel. Expiry is authoritative, not the
     * stored status (a stale ACTIVE row with a past date is treated as lapsed), matching
     * SubscriptionService.view's read-time recomputation.
     */
    private fun paidEligibility(subscription: SubscriptionEntity?, now: Instant): RoutingReason {
        if (subscription == null) return RoutingReason.FREE_CHANNELS_ONLY
        if (subscription.tier == SubscriptionTier.BASE) return RoutingReason.FREE_CHANNELS_ONLY
        if (subscription.status == SubscriptionStatus.NONE) return RoutingReason.FREE_CHANNELS_ONLY
        val expiry = subscription.expiryDate
            ?: return if (subscription.status == SubscriptionStatus.ACTIVE) {
                RoutingReason.ACTIVE_SUBSCRIBER
            } else {
                RoutingReason.FREE_CHANNELS_ONLY
            }
        val graceCutoff = now.minus(Duration.ofDays(graceDays))
        if (!expiry.isAfter(graceCutoff)) return RoutingReason.FREE_CHANNELS_ONLY
        return if (subscription.status == SubscriptionStatus.ACTIVE && expiry.isAfter(now)) {
            RoutingReason.ACTIVE_SUBSCRIBER
        } else {
            RoutingReason.EXPIRED_WITHIN_GRACE
        }
    }

    private fun denied(reason: RoutingReason) = Decision(inApp = false, push = false, sms = false, reason = reason)
}
