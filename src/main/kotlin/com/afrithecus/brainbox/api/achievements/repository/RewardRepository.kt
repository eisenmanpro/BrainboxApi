package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.RewardEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RewardRepository : JpaRepository<RewardEntity, UUID> {

    fun findAllByOrderByXpCostAsc(): List<RewardEntity>
}
