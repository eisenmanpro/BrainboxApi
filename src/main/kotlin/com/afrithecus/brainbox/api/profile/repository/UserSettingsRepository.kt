package com.afrithecus.brainbox.api.profile.repository

import com.afrithecus.brainbox.api.profile.entity.UserSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserSettingsRepository : JpaRepository<UserSettingsEntity, UUID> {

    fun findByUserId(userId: UUID): UserSettingsEntity?
}
