package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Proactive monthly-renewal reminders (docs/ongoing/product_ops_roadmap.md item 2).
 *
 * The inbox derives renewal reminders on read, which means a guardian who never opens
 * the app is never reminded and school operations quietly degrade. This daily sweep
 * materialises those reminders for the paying accounts and pushes them, and gives
 * teachers an aggregate nudge so they can chase guardians for their own classes.
 *
 * Idempotent: [NotificationService.materialiseAndPush] only pushes rows it creates, and
 * dedupe keys are per-threshold (active, expiring, urgent, expired), so each threshold
 * notifies once. Failures are logged per account and never abort the sweep.
 */
@Service
class SubscriptionReminderScheduler(
    private val subscriptions: SubscriptionRepository,
    private val users: UserRepository,
    private val notifications: NotificationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 900_000, fixedDelay = 86_400_000)
    fun sweep() {
        // Payers: anyone on a paid plan, i.e. the account that actually renews.
        val payers = subscriptions.findAll()
            .filter { it.tier != SubscriptionTier.BASE }
            .map { it.userId }
            .toSet()
        // Teachers: one aggregate nudge each, for the learners they teach.
        val teachers = users.findAllByRoleAndIsActiveTrue(Role.TEACHER).map { it.id }

        (payers + teachers).forEach(::pushSafely)
        log.info("renewal reminder sweep: {} payer(s), {} teacher(s)", payers.size, teachers.size)
    }

    private fun pushSafely(userId: UUID) {
        runCatching { notifications.materialiseAndPush(userId) }
            .onFailure { log.warn("renewal reminder failed for {}: {}", userId, it.message) }
    }
}
