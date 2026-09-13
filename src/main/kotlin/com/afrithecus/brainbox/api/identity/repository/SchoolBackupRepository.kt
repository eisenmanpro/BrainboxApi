package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolBackupEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolBackupRepository : JpaRepository<SchoolBackupEntity, UUID>
