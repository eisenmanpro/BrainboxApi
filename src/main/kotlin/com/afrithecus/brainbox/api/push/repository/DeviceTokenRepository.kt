package com.afrithecus.brainbox.api.push.repository

import com.afrithecus.brainbox.api.push.entity.DeviceTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DeviceTokenRepository : JpaRepository<DeviceTokenEntity, UUID> {

    fun findByToken(token: String): DeviceTokenEntity?

    fun findAllByUserId(userId: UUID): List<DeviceTokenEntity>

    fun deleteByUserIdAndToken(userId: UUID, token: String)
}
