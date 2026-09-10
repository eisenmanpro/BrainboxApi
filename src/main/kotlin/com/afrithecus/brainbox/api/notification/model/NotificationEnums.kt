package com.afrithecus.brainbox.api.notification.model

/** Notification vocabulary; names match the Android AppNotification models. */
enum class NotificationType { ANNOUNCEMENT, HOMEWORK, EXAM, LIVE_CLASS, MESSAGE, SYSTEM, REWARD, ATTENDANCE }

enum class NotificationUrgency { URGENT, HIGH, NORMAL, LOW }

enum class NotificationPriority { SYSTEM, HIGH, NORMAL, LOW }

enum class NewsStatus { DRAFT, PUBLISHED }
