package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.ConsoleRoleEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConsoleRoleRepository : JpaRepository<ConsoleRoleEntity, UUID> {

    fun findByNameIgnoreCase(name: String): ConsoleRoleEntity?
}
