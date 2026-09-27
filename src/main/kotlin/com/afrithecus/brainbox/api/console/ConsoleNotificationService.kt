package com.afrithecus.brainbox.api.console

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.console.web.ConsoleAudiencePreview
import com.afrithecus.brainbox.api.console.web.ConsoleAutomationRuleRequest
import com.afrithecus.brainbox.api.console.web.ConsoleAutomationRuleView
import com.afrithecus.brainbox.api.console.web.ConsoleNotificationView
import com.afrithecus.brainbox.api.console.web.ConsoleSendRequest
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.identity.AuditLogService
import com.afrithecus.brainbox.api.identity.PlatformAccessService
import com.afrithecus.brainbox.api.identity.PlatformPermission
import com.afrithecus.brainbox.api.identity.model.CurrentUser
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.messaging.PlatformSender
import com.afrithecus.brainbox.api.messaging.entity.MessageEntity
import com.afrithecus.brainbox.api.messaging.model.Folder
import com.afrithecus.brainbox.api.messaging.web.MessagePayload
import com.afrithecus.brainbox.api.notification.NotificationService
import com.afrithecus.brainbox.api.identity.model.SubscriptionStatus
import com.afrithecus.brainbox.api.subscription.repository.SubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Notification composition and automation.
 *
 * The audience is **resolved at send time**, never stored as a recipient list, so "every teacher
 * in this school" means whoever that is when the message goes out — including for a scheduled
 * send, which is the whole point of scheduling one.
 *
 * Automation is a small closed set of triggers rather than a rule engine: each trigger is
 * computable from data the API already keeps, so a stored rule can never promise something
 * nobody evaluates.
 */
@Service
class ConsoleNotificationService(
    private val notifications: ConsoleNotificationRepository,
    private val rules: ConsoleAutomationRuleRepository,
    private val users: UserRepository,
    private val messageRepository: com.afrithecus.brainbox.api.messaging.repository.MessageRepository,
    private val mailbox: com.afrithecus.brainbox.api.messaging.MessagingService,
    private val subscriptions: SubscriptionRepository,
    private val notificationService: NotificationService,
    private val access: PlatformAccessService,
    private val audit: AuditLogService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ------------------------------------------------------------ composition

    /** Queues a notification: sent now when [ConsoleSendRequest.scheduledAt] is null. */
    @Transactional
    fun send(current: CurrentUser, request: ConsoleSendRequest): ConsoleNotificationView =
        compose(current, request, CHANNEL_NOTIFICATION, PlatformPermission.NOTIFICATIONS_MANAGE)

    /**
     * Writes a message into the audience's inboxes as **Brainbox**. It arrives like any other
     * message (no impersonation of a person, no notification row), which is what makes it
     * readable and replyable from the message centre.
     */
    @Transactional
    fun sendMessage(current: CurrentUser, request: ConsoleSendRequest): ConsoleNotificationView =
        compose(current, request, CHANNEL_MESSAGE, PlatformPermission.MESSAGES_MANAGE)

    private fun compose(
        current: CurrentUser,
        request: ConsoleSendRequest,
        channel: String,
        capability: PlatformPermission,
    ): ConsoleNotificationView {
        val actor = access.requirePermission(current, capability)
        val audienceType = request.audienceType.trim().uppercase()
        if (audienceType !in AUDIENCE_TYPES) {
            throw invalidArgument("audienceType must be one of " + AUDIENCE_TYPES.joinToString(", "))
        }
        val audienceValue = request.audienceValue?.trim()?.takeIf { it.isNotEmpty() }
        if (audienceType != "ALL" && audienceValue == null) {
            throw invalidArgument("audienceValue is required for a " + audienceType + " audience")
        }
        if (request.title.isBlank()) throw invalidArgument("A title is required")
        if (request.body.isBlank()) throw invalidArgument("A message body is required")

        val scheduledAt = request.scheduledAt?.let { Instant.ofEpochMilli(it) }
        val row = notifications.save(
            ConsoleNotificationEntity().apply {
                title = request.title.trim().take(200)
                body = request.body.trim().take(2000)
                this.channel = channel
                this.audienceType = audienceType
                this.audienceValue = audienceValue
                this.scheduledAt = scheduledAt
                status = if (scheduledAt == null || !scheduledAt.isAfter(clock.instant())) "SCHEDULED" else "SCHEDULED"
                createdBy = actor.id
            }
        )
        // A send with no future schedule goes out immediately (and is marked SENT).
        if (scheduledAt == null || !scheduledAt.isAfter(clock.instant())) {
            deliver(row)
        }
        audit.recordPlatform(
            actor = actor,
            action = if (channel == CHANNEL_MESSAGE) "console_message_sent" else "console_notification_queued",
            target = (if (channel == CHANNEL_MESSAGE) "message:" else "notification:") + row.id,
            detail = row.title + " -> " + row.audienceType + (audienceValue?.let { ":" + it } ?: "") +
                ", recipients=" + row.recipientCount,
        )
        return row.toView()
    }

    @Transactional(readOnly = true)
    fun list(
        current: CurrentUser,
        status: String?,
        limit: Int,
        channel: String = CHANNEL_NOTIFICATION,
        capability: PlatformPermission = PlatformPermission.NOTIFICATIONS_MANAGE,
    ): List<ConsoleNotificationView> {
        access.requirePermission(current, capability)
        val page = PageRequest.of(0, limit.coerceIn(1, 200))
        val wanted = status?.trim()?.takeIf { it.isNotEmpty() }
        val rows = if (wanted == null) {
            notifications.findAllByChannelOrderByCreatedAtDesc(channel, page)
        } else {
            val normalized = wanted.uppercase()
            if (normalized !in NOTIFICATION_STATUSES) throw invalidArgument("Unknown status '$wanted'")
            notifications.findAllByChannelAndStatusOrderByCreatedAtDesc(channel, normalized, page)
        }
        return rows.map { it.toView() }
    }

    /** Resolves the audience now, so the console can show who a send would reach before sending. */
    @Transactional(readOnly = true)
    fun previewAudience(
        current: CurrentUser,
        audienceType: String,
        audienceValue: String?,
        capability: PlatformPermission = PlatformPermission.NOTIFICATIONS_MANAGE,
    ): ConsoleAudiencePreview {
        access.requirePermission(current, capability)
        val recipients = resolveAudience(audienceType.trim().uppercase(), audienceValue?.trim())
        return ConsoleAudiencePreview(
            audienceType = audienceType.trim().uppercase(),
            audienceValue = audienceValue?.trim(),
            recipientCount = recipients.size,
        )
    }

    /**
     * The other half of a direct message: what users wrote back to Brainbox. Read-only history
     * plus an explicit "read" marker, so the console can tell new answers from handled ones.
     */
    @Transactional(readOnly = true)
    fun replies(current: CurrentUser, limit: Int = 50): List<MessagePayload> {
        access.requirePermission(current, PlatformPermission.MESSAGES_MANAGE)
        return mailbox.platformInbox(limit)
    }

    @Transactional
    fun markReplyRead(current: CurrentUser, messageId: String): MessagePayload {
        val actor = access.requirePermission(current, PlatformPermission.MESSAGES_MANAGE)
        if (!mailbox.markPlatformInboxRead(messageId)) throw notFound("Reply not found")
        audit.recordPlatform(
            actor = actor,
            action = "console_message_reply_read",
            target = "message:" + messageId,
            detail = null,
        )
        return mailbox.platformInbox(200).firstOrNull { it.id == messageId }
            ?: throw notFound("Reply not found")
    }

    @Transactional
    fun cancel(
        current: CurrentUser,
        notificationId: String,
        capability: PlatformPermission = PlatformPermission.NOTIFICATIONS_MANAGE,
        channel: String = CHANNEL_NOTIFICATION,
    ): ConsoleNotificationView {
        val actor = access.requirePermission(current, capability)
        val row = requireNotification(notificationId)
        if (row.status != "SCHEDULED") throw invalidArgument("Only a scheduled message can be cancelled")
        row.status = "CANCELLED"
        notifications.save(row)
        val isMessage = channel == CHANNEL_MESSAGE
        audit.recordPlatform(
            actor = actor,
            action = if (isMessage) "console_message_cancelled" else "console_notification_cancelled",
            target = (if (isMessage) "message:" else "notification:") + row.id,
            detail = row.title,
        )
        return row.toView()
    }

    // ------------------------------------------------------------- automation

    @Transactional(readOnly = true)
    fun rules(current: CurrentUser): List<ConsoleAutomationRuleView> {
        access.requirePermission(current, PlatformPermission.NOTIFICATIONS_MANAGE)
        return rules.findAllByOrderByCreatedAtDesc().map { it.toView() }
    }

    @Transactional
    fun createRule(current: CurrentUser, request: ConsoleAutomationRuleRequest): ConsoleAutomationRuleView {
        val actor = access.requirePermission(current, PlatformPermission.NOTIFICATIONS_MANAGE)
        val trigger = request.triggerType.trim().uppercase()
        if (trigger !in TRIGGER_TYPES) throw invalidArgument("triggerType must be one of " + TRIGGER_TYPES.joinToString(", "))
        if (request.name.isBlank()) throw invalidArgument("A rule name is required")
        if (request.title.isBlank() || request.body.isBlank()) {
            throw invalidArgument("A rule needs a title and a body")
        }
        val threshold = request.thresholdDays.coerceIn(1, 90)
        rules.findByNameIgnoreCase(request.name.trim())?.let { throw invalidArgument("A rule with that name already exists") }
        val row = rules.save(
            ConsoleAutomationRuleEntity().apply {
                name = request.name.trim().take(120)
                triggerType = trigger
                thresholdDays = threshold
                title = request.title.trim().take(200)
                body = request.body.trim().take(2000)
                enabled = request.enabled
                createdBy = actor.id
            }
        )
        audit.recordPlatform(
            actor = actor,
            action = "console_automation_rule_created",
            target = "rule:" + row.id,
            detail = row.name + " on " + row.triggerType,
        )
        return row.toView()
    }

    @Transactional
    fun setRuleEnabled(current: CurrentUser, ruleId: String, enabled: Boolean): ConsoleAutomationRuleView {
        val actor = access.requirePermission(current, PlatformPermission.NOTIFICATIONS_MANAGE)
        val row = requireRule(ruleId)
        row.enabled = enabled
        rules.save(row)
        audit.recordPlatform(
            actor = actor,
            action = if (enabled) "console_automation_rule_enabled" else "console_automation_rule_disabled",
            target = "rule:" + row.id,
            detail = row.name,
        )
        return row.toView()
    }

    /** Deletes a rule; the audit keeps the record of what it was. */
    @Transactional
    fun deleteRule(current: CurrentUser, ruleId: String): Map<String, String> {
        val actor = access.requirePermission(current, PlatformPermission.NOTIFICATIONS_MANAGE)
        val row = requireRule(ruleId)
        rules.delete(row)
        audit.recordPlatform(
            actor = actor,
            action = "console_automation_rule_deleted",
            target = "rule:" + row.id,
            detail = row.name,
        )
        return mapOf("status" to "DELETED", "ruleId" to row.id.toString())
    }

    /**
     * Sends what is due and evaluates the automation triggers. Runs on a fixed delay; a send
     * failure marks the message FAILED rather than retrying forever, so the console can see it.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    @Transactional
    fun drainAndAutomate() {
        val now = clock.instant()
        notifications.findAllByStatusAndScheduledAtLessThanEqual("SCHEDULED", now).forEach { row ->
            runCatching { deliver(row) }.onFailure { failure ->
                log.warn("notification {} failed: {}", row.id, failure.message)
                row.status = "FAILED"
                notifications.save(row)
            }
        }
        runCatching { runExpiringRules(now) }.onFailure { failure ->
            log.warn("automation run failed: {}", failure.message)
        }
        runCatching { runExpiredRules(now) }.onFailure { failure ->
            log.warn("automation run failed: {}", failure.message)
        }
    }

    /** One reminder per subscriber per rule window, tracked by the rule's last run time. */
    private fun runExpiringRules(now: Instant) {
        rules.findAllByEnabledTrueAndTriggerType("SUBSCRIPTION_EXPIRING").forEach { rule ->
            val windowEnd = now.plus(Duration.ofDays(rule.thresholdDays.toLong()))
            val due = subscriptions.findAll().filter { row ->
                row.status == SubscriptionStatus.ACTIVE &&
                    row.expiryDate != null &&
                    row.expiryDate!!.isAfter(now) &&
                    row.expiryDate!!.isBefore(windowEnd) &&
                    (rule.lastRunAt == null || row.expiryDate!!.isBefore(rule.lastRunAt!!.plus(Duration.ofDays(rule.thresholdDays.toLong()))))
            }
            if (due.isEmpty()) return@forEach
            val sent = due.count { row -> deliverToUser(row.userId, rule.title, rule.body) }
            rule.lastRunAt = now
            rules.save(rule)
            log.info("automation '{}' notified {} expiring subscriber(s)", rule.name, sent)
        }
    }

    private fun runExpiredRules(now: Instant) {
        rules.findAllByEnabledTrueAndTriggerType("SUBSCRIPTION_EXPIRED").forEach { rule ->
            val expired = subscriptions.findAll().filter { row ->
                row.expiryDate != null &&
                    row.expiryDate!!.isBefore(now) &&
                    row.status != SubscriptionStatus.EXPIRED &&
                    (rule.lastRunAt == null || rule.lastRunAt!!.isBefore(now.minus(Duration.ofDays(1))))
            }
            if (expired.isEmpty()) return@forEach
            val sent = expired.count { row ->
                row.status = SubscriptionStatus.EXPIRED
                subscriptions.save(row)
                deliverToUser(row.userId, rule.title, rule.body)
            }
            rule.lastRunAt = now
            rules.save(rule)
            log.info("automation '{}' expired and notified {} subscription(s)", rule.name, sent)
        }
    }

    // --------------------------------------------------------------- internals

    /** Delivers to the resolved audience on the row's channel and marks the row SENT. */
    private fun deliver(row: ConsoleNotificationEntity) {
        val recipients = resolveAudience(row.audienceType, row.audienceValue)
        if (row.channel == CHANNEL_MESSAGE) {
            requirePlatformSender()
            recipients.forEach { user -> deliverInboxMessage(row, user.id) }
        } else {
            recipients.forEach { user -> deliverToUser(user.id, row.title, row.body) }
        }
        row.recipientCount = recipients.size
        row.status = "SENT"
        row.sentAt = clock.instant()
        notifications.save(row)
    }

    /**
     * Writes one inbox row from the platform account. One row per recipient, sharing a message
     * group with the platform's own "sent" copy, so the exchange reads as normal correspondence
     * in the message centre.
     *
     * The device is pushed as well (`type = MESSAGE`, route `message_centre`): a message nobody
     * is told about is a message nobody reads. The push is not an in-app row — the message centre
     * is where the message lives — but the client renders the push in its own feed, exactly as it
     * does for every other feature's push.
     */
    private fun deliverInboxMessage(row: ConsoleNotificationEntity, recipientId: UUID): Boolean =
        runCatching {
            val group = "console_" + row.id + "_" + recipientId
            messageRepository.save(
                MessageEntity().apply {
                    msgGroup = group
                    senderId = PlatformSender.USER_ID
                    this.recipientId = recipientId
                    subject = row.title
                    body = row.body
                    folder = Folder.inbox
                },
            )
            messageRepository.save(
                MessageEntity().apply {
                    msgGroup = group
                    senderId = PlatformSender.USER_ID
                    this.recipientId = recipientId
                    subject = row.title
                    body = row.body
                    folder = Folder.sent
                },
            )
            notificationService.pushToUser(
                userId = recipientId,
                title = row.title,
                message = preview(row.body),
                type = PlatformMessagePush.TYPE,
                actionRoute = PlatformMessagePush.ROUTE,
                actionLabel = PlatformMessagePush.ACTION_LABEL,
                metadata = mapOf(
                    "notificationId" to row.id.toString(),
                    "sender" to PlatformSender.NAME,
                ),
            )
        }.isSuccess

    /** One line for the lock screen: whitespace collapsed and cut at a word-safe length. */
    private fun preview(body: String, limit: Int = 160): String {
        val flattened = body.replace(Regex("\\s+"), " ").trim()
        if (flattened.length <= limit) return flattened
        val cut = flattened.take(limit)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > limit / 2) cut.take(lastSpace) else cut).trimEnd() + "..."
    }

    /** The contract a device needs to open a pushed direct message. Pinned by the client. */
    private object PlatformMessagePush {
        val TYPE = com.afrithecus.brainbox.api.notification.model.NotificationType.MESSAGE
        const val ROUTE = "message_centre"
        const val ACTION_LABEL = "Open"
    }

    /** The seeded platform account must exist; without it a message has no author. */
    private fun requirePlatformSender() {
        if (users.findById(PlatformSender.USER_ID).orElse(null) == null) {
            throw IllegalStateException(
                "The platform message sender (" + PlatformSender.NAME + ") is missing; " +
                    "the baseline migration seeds it",
            )
        }
    }

    /**
     * The server-originated path: the recipient is not the actor, so this deliberately bypasses
     * the user-facing self check and pushes exactly as feature fan-out does.
     */
    private fun deliverToUser(userId: UUID, title: String, body: String): Boolean =
        runCatching {
            notificationService.notifyUser(
                userId = userId,
                title = title,
                message = body,
                type = com.afrithecus.brainbox.api.notification.model.NotificationType.ANNOUNCEMENT,

            )
        }.isSuccess

    /** The audience, resolved from live data. An unknown audience value reaches nobody. */
    private fun resolveAudience(audienceType: String, audienceValue: String?): List<com.afrithecus.brainbox.api.identity.entity.UserEntity> {
        val all = users.findAll().filter { it.isActive }
        return when (audienceType) {
            "ALL" -> all
            "ROLE" -> {
                val role = runCatching { Role.valueOf(audienceValue?.uppercase().orEmpty()) }.getOrNull()
                    ?: return emptyList()
                all.filter { it.role == role }
            }
            "SCHOOL" -> {
                val id = runCatching { UUID.fromString(audienceValue.orEmpty()) }.getOrNull() ?: return emptyList()
                all.filter { it.schoolId == id }
            }
            "GRADE" -> all.filter { it.gradeLevel.equals(audienceValue, ignoreCase = true) }
            "CLASS" -> {
                val id = runCatching { UUID.fromString(audienceValue.orEmpty()) }.getOrNull() ?: return emptyList()
                all.filter { it.provisionedClassId == id }
            }
            "USER" -> {
                val id = runCatching { UUID.fromString(audienceValue.orEmpty()) }.getOrNull() ?: return emptyList()
                all.filter { it.id == id }
            }
            else -> emptyList()
        }
    }

    private fun requireNotification(id: String): ConsoleNotificationEntity {
        val uuid = runCatching { UUID.fromString(id.trim()) }.getOrNull()
            ?: throw invalidArgument("notificationId is not a valid identifier")
        return notifications.findById(uuid).orElse(null) ?: throw notFound("Notification not found")
    }

    private fun requireRule(id: String): ConsoleAutomationRuleEntity {
        val uuid = runCatching { UUID.fromString(id.trim()) }.getOrNull()
            ?: throw invalidArgument("ruleId is not a valid identifier")
        return rules.findById(uuid).orElse(null) ?: throw notFound("Automation rule not found")
    }

    private fun ConsoleNotificationEntity.toView() = ConsoleNotificationView(
        notificationId = id.toString(),
        channel = channel,
        title = title,
        body = body,
        audienceType = audienceType,
        audienceValue = audienceValue,
        scheduledAt = scheduledAt?.toEpochMilli(),
        status = status,
        recipientCount = recipientCount,
        sentAt = sentAt?.toEpochMilli(),
        createdAt = createdAt.toEpochMilli(),
    )

    private fun ConsoleAutomationRuleEntity.toView() = ConsoleAutomationRuleView(
        ruleId = id.toString(),
        name = name,
        triggerType = triggerType,
        thresholdDays = thresholdDays,
        title = title,
        body = body,
        enabled = enabled,
        lastRunAt = lastRunAt?.toEpochMilli(),
    )

    companion object {
        const val CHANNEL_NOTIFICATION = "NOTIFICATION"
        const val CHANNEL_MESSAGE = "MESSAGE"
        val AUDIENCE_TYPES = setOf("ALL", "ROLE", "SCHOOL", "GRADE", "CLASS", "USER")
        val NOTIFICATION_STATUSES = setOf("SCHEDULED", "SENT", "CANCELLED", "FAILED")
        val TRIGGER_TYPES = setOf("SUBSCRIPTION_EXPIRING", "SUBSCRIPTION_EXPIRED")
    }
}
