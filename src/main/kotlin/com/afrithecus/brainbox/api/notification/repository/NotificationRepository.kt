package com.afrithecus.brainbox.api.notification.repository

import com.afrithecus.brainbox.api.notification.entity.NotificationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationRepository : JpaRepository<NotificationEntity, UUID> {

    fun findAllByUserIdAndIsArchivedFalseOrderByCreatedAtDesc(userId: UUID): List<NotificationEntity>

    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<NotificationEntity>

    fun findByUserIdAndDedupeKey(userId: UUID, dedupeKey: String): NotificationEntity?

    fun findByIdAndUserId(id: UUID, userId: UUID): NotificationEntity?

    fun countByUserIdAndIsReadFalseAndIsArchivedFalse(userId: UUID): Long

    fun findAllByUserIdAndDedupeKeyIsNotNull(userId: UUID): List<NotificationEntity>

    fun deleteAllByUserId(userId: UUID)
}
