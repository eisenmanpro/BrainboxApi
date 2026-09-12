package com.afrithecus.brainbox.api.notification.entity

import com.afrithecus.brainbox.api.common.jpa.BaseEntity
import com.afrithecus.brainbox.api.notification.model.NotificationPriority
import com.afrithecus.brainbox.api.notification.model.NotificationType
import com.afrithecus.brainbox.api.notification.model.NotificationUrgency
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A delivered notification for one user (doc 05 §5). */
@Entity
@Table(name = "notifications")
class NotificationEntity : BaseEntity() {

    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID.randomUUID()

    @Column(nullable = false, length = 220)
    var title: String = ""

    @Column(nullable = false, columnDefinition = "text")
    var message: String = ""

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: NotificationType = NotificationType.SYSTEM

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var urgency: NotificationUrgency = NotificationUrgency.NORMAL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var priority: NotificationPriority = NotificationPriority.NORMAL

    @Column(name = "action_route", length = 255)
    var actionRoute: String? = null

    @Column(name = "action_label", length = 64)
    var actionLabel: String? = null

    /** JSON object string of deep-link extras. */
    @Column(columnDefinition = "text")
    var metadata: String? = null

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false

    @Column(name = "read_at")
    var readAt: Instant? = null

    @Column(name = "is_archived", nullable = false)
    var isArchived: Boolean = false

    /** Stable key for server-generated notifications; null for user/announcement rows. */
    @Column(name = "dedupe_key", length = 200)
    var dedupeKey: String? = null
}
