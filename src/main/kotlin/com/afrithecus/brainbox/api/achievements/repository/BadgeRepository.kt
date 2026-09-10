package com.afrithecus.brainbox.api.achievements.repository

import com.afrithecus.brainbox.api.achievements.entity.BadgeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BadgeRepository : JpaRepository<BadgeEntity, UUID>
