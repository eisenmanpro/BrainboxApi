package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolConfigRepository : JpaRepository<SchoolConfigEntity, UUID>
