package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolRepository : JpaRepository<SchoolEntity, UUID> {
    fun findByNameIgnoreCase(name: String): SchoolEntity?
}
