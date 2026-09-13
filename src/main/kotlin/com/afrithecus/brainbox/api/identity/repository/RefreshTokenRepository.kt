package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.RefreshTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    fun findByFamilyOrderByCreatedAtDesc(family: UUID): List<RefreshTokenEntity>

    fun countByFamily(family: UUID): Long

    fun findAllBySessionIdAndRevokedFalse(sessionId: UUID): List<RefreshTokenEntity>

    fun findAllByUserIdAndRevokedFalse(userId: UUID): List<RefreshTokenEntity>

    /** Removes refresh tokens that can no longer be exchanged (maintenance job). */
    @Transactional
    fun deleteByExpiresAtBefore(expiresAt: Instant): Long
}
