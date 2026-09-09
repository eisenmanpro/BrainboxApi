package com.afrithecus.brainbox.api.common.idempotency

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface IdempotencyRepository : JpaRepository<IdempotencyRecordEntity, UUID> {

    fun findByKeyHash(keyHash: String): IdempotencyRecordEntity?

    fun deleteByExpiresAtBefore(now: Instant): Long
}
