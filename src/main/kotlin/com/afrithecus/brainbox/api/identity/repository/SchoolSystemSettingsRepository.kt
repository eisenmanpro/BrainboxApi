package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolSystemSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolSystemSettingsRepository : JpaRepository<SchoolSystemSettingsEntity, UUID>
