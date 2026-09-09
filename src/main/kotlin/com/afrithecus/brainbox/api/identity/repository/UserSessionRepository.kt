package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.UserSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSessionRepository : JpaRepository<UserSessionEntity, UUID> {

    fun findByIdAndIsActiveTrue(id: UUID): UserSessionEntity?

    fun findTopByUserIdAndDeviceIdAndIsActiveTrueOrderByLastActiveAtDesc(
        userId: UUID,
        deviceId: String,
    ): UserSessionEntity?

    /** Oldest-first active sessions of a user, used to evict beyond the cap. */
    fun findAllByUserIdAndIsActiveTrueOrderByLastActiveAtAscIdAsc(userId: UUID): List<UserSessionEntity>

    fun findAllByUserIdAndIsActiveTrue(userId: UUID): List<UserSessionEntity>
}
