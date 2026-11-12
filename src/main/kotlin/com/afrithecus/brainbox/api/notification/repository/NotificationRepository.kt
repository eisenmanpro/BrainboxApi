package com.afrithecus.brainbox.api.notification.repository

import com.afrithecus.brainbox.api.notification.entity.NotificationEntity
import com.afrithecus.brainbox.api.notification.model.NotificationType
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface NotificationRepository : JpaRepository<NotificationEntity, UUID> {

    fun findAllByUserIdAndIsArchivedFalseOrderByCreatedAtDesc(userId: UUID): List<NotificationEntity>

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<NotificationEntity>

    fun findByUserIdAndDedupeKey(userId: UUID, dedupeKey: String): NotificationEntity?

    fun findByIdAndUserId(id: UUID, userId: UUID): NotificationEntity?

    fun countByUserIdAndIsReadFalseAndIsArchivedFalse(userId: UUID): Long

    fun findAllByUserIdAndDedupeKeyIsNotNull(userId: UUID): List<NotificationEntity>

    fun deleteAllByUserId(userId: UUID)

    /**
     * P4.1: the read-ack fallback looks for the in-app ATTENDANCE rows written for a recipient
     * today. The learner is matched from the row metadata by the caller.
     */
    fun findAllByUserIdAndTypeAndCreatedAtBetweenOrderByCreatedAtAsc(
        userId: UUID,
        type: NotificationType,
        from: Instant,
        to: Instant,
    ): List<NotificationEntity>
}
