package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.UserSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSessionRepository : JpaRepository<UserSessionEntity, UUID> {

    fun findByIdAndIsActiveTrue(id: UUID): UserSessionEntity?

    fun countByUserIdAndIsActiveTrue(userId: UUID): Long

    fun findTopByUserIdAndDeviceIdAndIsActiveTrueOrderByLastActiveAtDesc(
        userId: UUID,
        deviceId: String,
    ): UserSessionEntity?

    fun countByUserIdAndDeviceIdAndIsActiveTrue(userId: UUID, deviceId: String): Long
}
