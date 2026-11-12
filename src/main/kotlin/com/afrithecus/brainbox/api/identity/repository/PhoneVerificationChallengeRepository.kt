package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.PhoneVerificationChallengeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface PhoneVerificationChallengeRepository : JpaRepository<PhoneVerificationChallengeEntity, UUID> {

    fun findTopByUserIdAndConsumedFalseOrderByCreatedAtDesc(userId: UUID): PhoneVerificationChallengeEntity?

    fun findAllByUserIdAndConsumedFalse(userId: UUID): List<PhoneVerificationChallengeEntity>

    fun countByUserIdAndCreatedAtAfter(userId: UUID, since: Instant): Long

    fun deleteByExpiresAtBefore(cutoff: Instant): Long
}
