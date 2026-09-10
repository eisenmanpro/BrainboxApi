package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.XpEventEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Projection for weekly XP totals. */
interface UserXpTotal {
    fun getUserId(): UUID
    fun getTotal(): Long
}

interface XpEventRepository : JpaRepository<XpEventEntity, UUID> {

    fun findAllByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId: UUID, since: Instant): List<XpEventEntity>

    @Query(
        "SELECT e.userId AS userId, SUM(e.amount) AS total FROM XpEventEntity e " +
            "WHERE e.createdAt >= :since GROUP BY e.userId ORDER BY SUM(e.amount) DESC"
    )
    fun weeklyTotals(@Param("since") since: Instant, pageable: Pageable): List<UserXpTotal>
}
