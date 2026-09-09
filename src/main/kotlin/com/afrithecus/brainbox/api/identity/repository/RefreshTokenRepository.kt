package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.RefreshTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {

    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    fun findByFamilyOrderByCreatedAtDesc(family: UUID): List<RefreshTokenEntity>

    fun countByFamily(family: UUID): Long

    fun findAllBySessionIdAndRevokedFalse(sessionId: UUID): List<RefreshTokenEntity>

    fun findAllByUserIdAndRevokedFalse(userId: UUID): List<RefreshTokenEntity>
}
