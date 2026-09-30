package com.afrithecus.brainbox.api.notification

import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.messaging.PlatformSender
import com.afrithecus.brainbox.api.messaging.entity.MessageEntity
import com.afrithecus.brainbox.api.messaging.model.Folder
import com.afrithecus.brainbox.api.messaging.repository.MessageRepository
import com.afrithecus.brainbox.api.notification.entity.NotificationEntity
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import com.afrithecus.brainbox.api.notification.repository.NotificationRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Tells a teacher, once, that the same workspace exists on the web
 * (docs/ongoing/product_ops_roadmap.md item 6).
 *
 * The mechanism is the platform's own account: a message from [PlatformSender] lands in the
 * teacher's message centre exactly like any other correspondence — readable and replyable — and a
 * SYSTEM notification carries the URL so the announcement still reaches a teacher who never opens
 * the inbox. Idempotent on the notification's dedupe key, so it is delivered once per account.
 */
@Service
class WebCounterpartNoticeService(
    private val messages: MessageRepository,
    private val notifications: NotificationRepository,
    private val notificationService: NotificationService,
    private val mapper: ObjectMapper,
    @Value("\${app.web-app-url:https://app.brainbox.com}") private val webAppUrl: String,
) {

    @Transactional
    fun ensureNotified(user: UserEntity) {
        if (user.role != Role.TEACHER) return
        if (notifications.findByUserIdAndDedupeKey(user.id, DEDUPE_KEY) != null) return

        val url = webAppUrl.trim().ifEmpty { DEFAULT_URL }
        val title = "Brainbox also has a web workspace"
        val body = "You can run your classroom from a computer too — mark attendance, set homework, " +
            "build exams and read reports at " + url + ". Sign in with the same account you use here."

        // One row per side of the exchange, sharing a group, so it reads as normal mail.
        val group = "web_counterpart_" + user.id
        listOf(Folder.inbox, Folder.sent).forEach { folder ->
            messages.save(
                MessageEntity().apply {
                    msgGroup = group
                    senderId = PlatformSender.USER_ID
                    recipientId = user.id
                    subject = title
                    this.body = body
                    this.folder = folder
                }
            )
        }

        val saved = notifications.save(
            NotificationEntity().apply {
                userId = user.id
                this.title = title
                this.message = body
                type = NotificationType.SYSTEM
                priority = NotificationPriority.SYSTEM
                urgency = NotificationUrgency.NORMAL
                actionRoute = "message_centre"
                actionLabel = "Open message"
                metadata = mapper.writeValueAsString(mapOf("webUrl" to url, "sender" to PlatformSender.NAME))
                dedupeKey = DEDUPE_KEY
            }
        )
        notificationService.pushToUser(
            userId = user.id,
            title = saved.title,
            message = saved.message,
            type = NotificationType.SYSTEM,
            actionRoute = saved.actionRoute,
            actionLabel = saved.actionLabel,
            metadata = mapOf("webUrl" to url, "sender" to PlatformSender.NAME),
        )
    }

    private companion object {
        const val DEDUPE_KEY = "web-counterpart"
        const val DEFAULT_URL = "https://app.brainbox.com"
    }
}
