package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.PasswordResetChallengeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface PasswordResetChallengeRepository : JpaRepository<PasswordResetChallengeEntity, UUID> {

    fun findTopByIdentifierAndConsumedFalseOrderByCreatedAtDesc(identifier: String): PasswordResetChallengeEntity?

    fun findAllByIdentifierAndConsumedFalse(identifier: String): List<PasswordResetChallengeEntity>

    fun findByResetTokenHashAndConsumedFalse(resetTokenHash: String): PasswordResetChallengeEntity?

    fun deleteByExpiresAtBefore(cutoff: Instant): Long
}
