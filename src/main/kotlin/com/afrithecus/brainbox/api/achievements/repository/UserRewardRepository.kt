package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.UserRewardEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRewardRepository : JpaRepository<UserRewardEntity, UUID> {

    fun findAllByUserId(userId: UUID): List<UserRewardEntity>

    fun findByUserIdAndRewardId(userId: UUID, rewardId: UUID): UserRewardEntity?
}
