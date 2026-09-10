package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.UserAchievementsEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserAchievementsRepository : JpaRepository<UserAchievementsEntity, UUID> {

    fun findByUserId(userId: UUID): UserAchievementsEntity?

    /** National rank denominator: how many higher-XP rows exist. */
    fun countByTotalXpGreaterThan(totalXp: Int): Long

    fun findAllByOrderByTotalXpDesc(pageable: Pageable): List<UserAchievementsEntity>

    @Query(
        "SELECT a FROM UserAchievementsEntity a WHERE a.userId IN " +
            "(SELECT u.id FROM UserEntity u WHERE u.schoolId = :schoolId) ORDER BY a.totalXp DESC"
    )
    fun topBySchool(@Param("schoolId") schoolId: UUID, pageable: Pageable): List<UserAchievementsEntity>
}
