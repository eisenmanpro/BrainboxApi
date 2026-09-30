package com.afrithecus.brainbox.api.notification.model

/** Notification vocabulary; names match the Android AppNotification models. */
enum class NotificationType {
    ANNOUNCEMENT, HOMEWORK, EXAM, LIVE_CLASS, MESSAGE, SYSTEM, REWARD, ATTENDANCE,
    /** Server-derived monthly subscription reminders (product_ops_roadmap item 2). */
    SUBSCRIPTION,
}

enum class NotificationUrgency { URGENT, HIGH, NORMAL, LOW }

enum class NotificationPriority { SYSTEM, HIGH, NORMAL, LOW }

enum class NewsStatus { DRAFT, PUBLISHED }
