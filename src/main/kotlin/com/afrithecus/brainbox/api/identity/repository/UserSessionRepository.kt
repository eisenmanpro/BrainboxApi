package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.UserSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UserSessionRepository : JpaRepository<UserSessionEntity, UUID> {

    /** Distinct signed-in users active since the cutoff, for the dashboard pulse. */
    @Query("SELECT COUNT(DISTINCT s.userId) FROM UserSessionEntity s WHERE s.isActive = true AND s.lastActiveAt >= :since")
    fun countActiveUsersSince(@Param("since") since: Instant): Long

    fun findByIdAndIsActiveTrue(id: UUID): UserSessionEntity?

    fun findTopByUserIdAndDeviceIdAndIsActiveTrueOrderByLastActiveAtDesc(
        userId: UUID,
        deviceId: String,
    ): UserSessionEntity?

    /** Oldest-first active sessions of a user, used to evict beyond the cap. */
    fun findAllByUserIdAndIsActiveTrueOrderByLastActiveAtAscIdAsc(userId: UUID): List<UserSessionEntity>

    fun findAllByUserIdAndIsActiveTrue(userId: UUID): List<UserSessionEntity>
}
