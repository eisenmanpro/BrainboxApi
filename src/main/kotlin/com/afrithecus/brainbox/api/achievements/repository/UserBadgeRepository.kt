package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.UserBadgeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserBadgeRepository : JpaRepository<UserBadgeEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<UserBadgeEntity>

    fun findByUserIdAndBadgeId(userId: UUID, badgeId: UUID): UserBadgeEntity?
}
