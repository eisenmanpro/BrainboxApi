package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.model.SubscriptionTier
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.notification.entity.NotificationEntity
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import com.afrithecus.brainbox.api.notification.web.AppNotificationPayload
import com.afrithecus.brainbox.api.notification.web.CreateNotificationRequest
import com.afrithecus.brainbox.api.push.PushFanoutService
import com.afrithecus.brainbox.api.push.PushMessage
import com.afrithecus.brainbox.api.subscription.SubscriptionService
import com.afrithecus.brainbox.api.subscription.SubscriptionView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Notification centre (doc 05 §5) plus server-derived subscription reminders.
 *
 * System notifications are materialised when the user reads their notifications
 * and deduplicated on a stable key, so no scheduler is required before the
 * background-job phase. Students and parents are reminded according to their own
 * subscription state; teachers are nudged to remind guardians when learners they
 * teach need a renewal.
 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val userRepository: UserRepository,
    private val subscriptionService: SubscriptionService,
    private val teacherClassRepository: TeacherClassRepository,
    private val classMembershipRepository: ClassMembershipRepository,
    private val pushFanout: PushFanoutService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional
    fun list(current: CurrentUser, userIdRaw: String, unreadOnly: Boolean, includeArchived: Boolean): List<AppNotificationPayload> {
        val userId = requireSelf(current, userIdRaw)
        val user = userRepository.findById(userId).orElseThrow { notFound("User not found") }
        syncSystemNotifications(user)
        val rows = if (includeArchived) {
            repository.findAllByUserIdOrderByCreatedAtDesc(userId)
        } else {
            repository.findAllByUserIdAndIsArchivedFalseOrderByCreatedAtDesc(userId)
        }
        return rows.filter { !unreadOnly || !it.isRead }.map(::payload)
    }

    @Transactional
    fun unreadCount(current: CurrentUser, userIdRaw: String): Long =
        repository.countByUserIdAndIsReadFalseAndIsArchivedFalse(requireSelf(current, userIdRaw))

    @Transactional
    fun create(current: CurrentUser, userIdRaw: String, request: CreateNotificationRequest): AppNotificationPayload {
        val userId = requireSelf(current, userIdRaw)
        val canSend = current.role == Role.TEACHER || current.role == Role.ADMIN || current.userId == userId
        if (!canSend) throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot send notifications to other users")
        val saved = repository.save(NotificationEntity().apply {
            this.userId = userId
            title = request.title.trim()
            message = request.message
            type = enumOr(NotificationType.entries, request.type, NotificationType.SYSTEM, "notification type")
            urgency = enumOr(NotificationUrgency.entries, request.urgency, NotificationUrgency.NORMAL, "urgency")
            priority = enumOr(NotificationPriority.entries, request.priority, NotificationPriority.NORMAL, "priority")
            actionRoute = request.actionRoute
            actionLabel = request.actionLabel
            metadata = mapper.writeValueAsString(request.metadata)
        })
        return payload(saved)
    }

    /**
     * Server-originated notification delivered to [userId]. Used by feature
     * fan-out (result publishing, reminders, moderation decisions) where the
     * actor is not the recipient, so it deliberately bypasses the self check in
     * [create].
     */
    @Transactional
    fun notifyUser(
        userId: UUID,
        title: String,
        message: String,
        type: NotificationType = NotificationType.SYSTEM,
        urgency: NotificationUrgency = NotificationUrgency.NORMAL,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        actionRoute: String? = null,
        actionLabel: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): AppNotificationPayload {
        val saved = repository.save(NotificationEntity().apply {
            this.userId = userId
            this.title = title.trim()
            this.message = message
            this.type = type
            this.urgency = urgency
            this.priority = priority
            this.actionRoute = actionRoute
            this.actionLabel = actionLabel
            this.metadata = mapper.writeValueAsString(metadata)
        })
        pushFanout.dispatch(
            userId,
            PushMessage(
                title = saved.title,
                message = saved.message,
                type = saved.type.name,
                actionRoute = saved.actionRoute,
                actionLabel = saved.actionLabel,
                urgency = saved.urgency.name,
                metadata = metadata,
            ),
        )
        return payload(saved)
    }

    /**
     * A push on its own, with **no in-app row**: for content that already has its own home in
     * the app (a direct message lives in the message centre) but should still reach the device.
     * Registration and delivery semantics are [notifyUser]'s, including the after-commit
     * dispatch, so a rolled-back transaction never pushes.
     */
    fun pushToUser(
        userId: UUID,
        title: String,
        message: String,
        type: NotificationType = NotificationType.MESSAGE,
        urgency: NotificationUrgency = NotificationUrgency.NORMAL,
        actionRoute: String? = null,
        actionLabel: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        pushFanout.dispatch(
            userId,
            PushMessage(
                title = title.trim(),
                message = message,
                type = type.name,
                actionRoute = actionRoute,
                actionLabel = actionLabel,
                urgency = urgency.name,
                metadata = metadata,
            ),
        )
    }

    @Transactional
    fun markRead(current: CurrentUser, notificationIdRaw: String) {
        val row = owned(current, notificationIdRaw)
        if (!row.isRead) {
            row.isRead = true
            row.readAt = clock.instant()
            repository.save(row)
        }
    }

    @Transactional
    fun markAllRead(current: CurrentUser, userIdRaw: String) {
        val userId = requireSelf(current, userIdRaw)
        val now = clock.instant()
        val unread = repository.findAllByUserIdAndIsArchivedFalseOrderByCreatedAtDesc(userId).filter { !it.isRead }
        unread.forEach {
            it.isRead = true
            it.readAt = now
        }
        repository.saveAll(unread)
    }

    @Transactional
    fun delete(current: CurrentUser, notificationIdRaw: String) {
        repository.delete(owned(current, notificationIdRaw))
    }

    @Transactional
    fun deleteAll(current: CurrentUser, userIdRaw: String) {
        repository.deleteAllByUserId(requireSelf(current, userIdRaw))
    }

    @Transactional
    fun archive(current: CurrentUser, notificationIdRaw: String) {
        val row = owned(current, notificationIdRaw)
        row.isArchived = true
        if (!row.isRead) {
            row.isRead = true
            row.readAt = clock.instant()
        }
        repository.save(row)
    }

    // ------------------------------------------------------------ system reminders

    private fun syncSystemNotifications(user: UserEntity, push: Boolean = false) {
        val batch = ReminderBatch()
        when (user.role) {
            Role.STUDENT, Role.PARENT -> subscriptionReminders(user, batch)
            Role.TEACHER -> teacherRenewalReminder(user, batch)
            Role.ADMIN -> Unit
        }
        // Drop server-generated subscription reminders whose condition no longer
        // holds. Other keyed notifications (e.g. chat fan-out) are left in place.
        repository.findAllByUserIdAndDedupeKeyIsNotNull(user.id)
            .filter { row -> REMINDER_KEY_PREFIXES.any { prefix -> row.dedupeKey!!.startsWith(prefix) } }
            .filter { it.dedupeKey !in batch.active }
            .forEach { repository.delete(it) }
        if (push) batch.created.forEach(::dispatchPush)
    }

    /**
     * Proactive renewal reminders (docs/ongoing/product_ops_roadmap.md item 2). The inbox
     * already derives these on read, but a parent who never opens the app would never be
     * reminded, so the daily sweep calls this: it materialises exactly what the inbox shows
     * and pushes only the rows this run created. Idempotent through the dedupe key, so each
     * threshold (14-day, 3-day, expired) notifies once rather than once per sweep.
     */
    @Transactional
    fun materialiseAndPush(userId: UUID) {
        val user = userRepository.findById(userId).orElse(null) ?: return
        syncSystemNotifications(user, push = true)
    }

    private fun dispatchPush(row: NotificationEntity) {
        pushFanout.dispatch(
            row.userId,
            PushMessage(
                title = row.title,
                message = row.message,
                type = row.type.name,
                actionRoute = row.actionRoute,
                actionLabel = row.actionLabel,
                urgency = row.urgency.name,
                metadata = parseMap(row.metadata),
            ),
        )
    }

    /** Reminder keys seen this run, plus the rows this run created (for pushing). */
    private class ReminderBatch {
        val active = linkedSetOf<String>()
        val created = mutableListOf<NotificationEntity>()
    }

    private fun subscriptionReminders(user: UserEntity, batch: ReminderBatch) {
        val targets = if (user.role == Role.PARENT) {
            userRepository.findByParentUserId(user.id).ifEmpty { listOf(user) }
        } else {
            listOf(user)
        }
        val now = clock.instant()
        for (target in targets) {
            val subscription = subscriptionService.view(target.id)
            val whose = if (user.role == Role.PARENT) target.name.ifBlank { "Your child" } + "'s" else "Your"
            val expiryKey = subscription.expiryDate?.let { Instant.ofEpochMilli(it).atZone(clock.zone).toLocalDate() } ?: LocalDate.now(clock)
            when {
                subscription.tier == SubscriptionTier.BASE.name || subscription.status == "NONE" -> upsert(batch = batch, 
                    userId = user.id,
                    key = "sub-free:" + target.id,
                    title = "Unlock full learning",
                    message = whose + " account is on the free plan. Subscribe to keep exam analytics, homework and the attendance register working.",
                    urgency = NotificationUrgency.HIGH,
                    route = "subscription",
                    label = "View plans",
                    metadata = mapOf("studentId" to target.id.toString()),
                )
                subscription.status == "EXPIRED" || (subscription.expiryDate != null && subscription.expiryDate <= now.toEpochMilli()) -> upsert(batch = batch, 
                    userId = user.id,
                    key = "sub-expired:" + target.id + ":" + expiryKey,
                    title = "Subscription expired",
                    message = whose + " " + subscription.tier + " monthly plan has expired. Renew to restore exam analytics, homework and the attendance register.",
                    urgency = NotificationUrgency.URGENT,
                    route = "subscription",
                    label = "Renew now",
                    metadata = mapOf("studentId" to target.id.toString(), "plan" to subscription.tier),
                )
                else -> {
                    val daysLeft = subscription.expiryDate?.let { Duration.between(now, Instant.ofEpochMilli(it)).toDays() } ?: Long.MAX_VALUE
                    when {
                        daysLeft <= URGENT_DAYS -> upsert(batch = batch, 
                            userId = user.id,
                            key = "sub-expiring-urgent:" + target.id + ":" + expiryKey,
                            title = "Subscription renews in " + daysLeft + " days",
                            message = whose + " " + subscription.tier + " monthly plan expires in " + daysLeft + " day(s). Renew now to keep exam analytics, homework and the attendance register running.",
                            urgency = NotificationUrgency.URGENT,
                            route = "subscription",
                            label = "Renew now",
                            metadata = mapOf("studentId" to target.id.toString(), "plan" to subscription.tier, "daysLeft" to daysLeft.toString()),
                        )
                        daysLeft <= EXPIRING_DAYS -> upsert(batch = batch, 
                            userId = user.id,
                            key = "sub-expiring:" + target.id + ":" + expiryKey,
                            title = "Subscription expires in " + daysLeft + " days",
                            message = whose + " " + subscription.tier + " monthly plan is nearing expiration. Renew early so school operations are not interrupted.",
                            urgency = NotificationUrgency.HIGH,
                            route = "subscription",
                            label = "Renew",
                            metadata = mapOf("studentId" to target.id.toString(), "plan" to subscription.tier, "daysLeft" to daysLeft.toString()),
                        )
                        else -> upsert(batch = batch, 
                            userId = user.id,
                            key = "sub-active:" + target.id + ":" + expiryKey,
                            title = "You're on the " + subscription.tier + " plan",
                            message = whose + " " + subscription.tier + " plan is active until " + formatDate(subscription.expiryDate) + ".",
                            urgency = NotificationUrgency.LOW,
                            route = "subscription",
                            label = "Manage plan",
                            metadata = mapOf("studentId" to target.id.toString(), "plan" to subscription.tier),
                        )
                    }
                }
            }
        }
    }

    private fun teacherRenewalReminder(teacher: UserEntity, batch: ReminderBatch) {
        val now = clock.instant()
        val studentIds = linkedSetOf<UUID>()
        teacherClassRepository.findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacher.id)
            .forEach { clazz -> classMembershipRepository.findAllByClassId(clazz.id).forEach { studentIds += it.studentId } }
        userRepository.findByJoinedTeacherId(teacher.id).forEach { studentIds += it.id }
        if (studentIds.isEmpty()) return
        val states = userRepository.findAllById(studentIds).filter { it.isActive }
            .associateWith { subscriptionService.view(it.id) }
        val needing = states.filter { (_, sub) -> needsRenewal(sub, now) }
        if (needing.isEmpty()) return
        val expired = needing.count { (_, sub) -> sub.status == "EXPIRED" || (sub.expiryDate != null && sub.expiryDate <= now.toEpochMilli()) }
        val names = needing.keys.take(3).joinToString(", ") { it.name }
        upsert(batch = batch, 
            userId = teacher.id,
            key = "teacher-sub-reminder",
            title = "Remind " + needing.size + " parent(s) to renew",
            message = needing.size.toString() + " of your learners need a subscription renewal (" + names +
                (if (needing.size > 3) " and others" else "") +
                "). Please remind their parents/guardians so exam analytics, homework and the attendance register keep working.",
            urgency = if (expired > 0) NotificationUrgency.URGENT else NotificationUrgency.HIGH,
            route = "teacher_dashboard",
            label = "Open dashboard",
            metadata = mapOf("studentsNeedingRenewal" to needing.size.toString(), "expired" to expired.toString()),
        )
    }

    private fun needsRenewal(subscription: SubscriptionView, now: Instant): Boolean {
        if (subscription.tier == SubscriptionTier.BASE.name || subscription.status == "NONE") return true
        if (subscription.status == "EXPIRED") return true
        val expiry = subscription.expiryDate ?: return false
        if (expiry <= now.toEpochMilli()) return true
        val daysLeft = Duration.between(now, Instant.ofEpochMilli(expiry)).toDays()
        return daysLeft <= EXPIRING_DAYS
    }

    private fun upsert(
        batch: ReminderBatch,
        userId: UUID,
        key: String,
        title: String,
        message: String,
        urgency: NotificationUrgency,
        route: String,
        label: String,
        metadata: Map<String, String>,
    ) {
        batch.active.add(key)
        val existing = repository.findByUserIdAndDedupeKey(userId, key)
        val metaJson = mapper.writeValueAsString(metadata)
        if (existing == null) {
            val saved = repository.save(NotificationEntity().apply {
                this.userId = userId
                this.title = title
                this.message = message
                type = NotificationType.SUBSCRIPTION
                priority = NotificationPriority.SYSTEM
                this.urgency = urgency
                actionRoute = route
                actionLabel = label
                this.metadata = metaJson
                dedupeKey = key
            })
            batch.created += saved
            return
        }
        // Refresh content but keep read state and original timestamp.
        existing.title = title
        existing.message = message
        existing.urgency = urgency
        existing.actionRoute = route
        existing.actionLabel = label
        existing.metadata = metaJson
        repository.save(existing)
    }

    // ------------------------------------------------------------ internals

    private fun payload(row: NotificationEntity): AppNotificationPayload {
        val created = row.createdAt.toEpochMilli()
        return AppNotificationPayload(
            id = row.id.toString(),
            title = row.title,
            message = row.message,
            timestamp = created,
            type = row.type.name,
            isRead = row.isRead,
            actionRoute = row.actionRoute,
            actionLabel = row.actionLabel,
            metadata = parseMap(row.metadata),
            urgency = row.urgency.name,
            priority = row.priority.name,
            createdAt = created,
        )
    }

    private fun owned(current: CurrentUser, idRaw: String): NotificationEntity {
        val id = runCatching { UUID.fromString(idRaw) }.getOrNull() ?: throw invalidArgument("notification id is not a valid identifier")
        return repository.findByIdAndUserId(id, current.userId) ?: throw notFound("Notification not found")
    }

    private fun requireSelf(current: CurrentUser, userIdRaw: String): UUID {
        if (userIdRaw != current.userId.toString()) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Cannot access another user's notifications")
        }
        return current.userId
    }

    private fun <T : Enum<T>> enumOr(values: List<T>, raw: String, fallback: T, label: String): T =
        values.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: if (raw.isBlank()) fallback else throw invalidArgument("Unknown " + label + ": " + raw)

    private fun formatDate(epochMillis: Long?): String =
        epochMillis?.let { DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(clock.zone)) } ?: ""

    private fun parseMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!node.isObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (entry in node.properties()) out[entry.key] = entry.value.asString()
        return out
    }

    private companion object {
        val REMINDER_KEY_PREFIXES = listOf("sub-", "teacher-sub-")
        const val EXPIRING_DAYS = 14L
        const val URGENT_DAYS = 3L
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    }
}
