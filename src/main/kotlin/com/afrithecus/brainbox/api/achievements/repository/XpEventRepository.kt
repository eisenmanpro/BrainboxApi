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

    /** Every user's XP since [since]; used by the admin repair (recalculate). */
    @Query(
        "SELECT e.userId AS userId, SUM(e.amount) AS total FROM XpEventEntity e " +
            "WHERE e.createdAt >= :since GROUP BY e.userId"
    )
    fun totalsSince(@Param("since") since: Instant): List<UserXpTotal>

    /** Admin leaderboard aggregation, optionally filtered by school and grade. */
    @Query(
        "SELECT e.userId AS userId, SUM(e.amount) AS total FROM XpEventEntity e, UserEntity u " +
            "WHERE e.userId = u.id AND e.createdAt >= :since " +
            "AND (:schoolId IS NULL OR u.schoolId = :schoolId) " +
            "AND (:grade IS NULL OR u.gradeLevel = :grade) " +
            "GROUP BY e.userId ORDER BY SUM(e.amount) DESC"
    )
    fun totalsSinceScoped(
        @Param("since") since: Instant,
        @Param("schoolId") schoolId: UUID?,
        @Param("grade") grade: String?,
        pageable: Pageable,
    ): List<UserXpTotal>
}
