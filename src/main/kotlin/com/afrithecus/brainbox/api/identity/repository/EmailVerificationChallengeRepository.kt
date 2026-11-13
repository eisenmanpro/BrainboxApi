package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.EmailVerificationChallengeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface EmailVerificationChallengeRepository : JpaRepository<EmailVerificationChallengeEntity, UUID> {

    fun findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(userId: UUID): EmailVerificationChallengeEntity?

    fun findAllByUserIdAndConsumedFalse(userId: UUID): List<EmailVerificationChallengeEntity>

    fun countByUserIdAndCreatedAtAfter(userId: UUID, since: Instant): Long

    fun deleteByExpiresAtBefore(cutoff: Instant)
}
