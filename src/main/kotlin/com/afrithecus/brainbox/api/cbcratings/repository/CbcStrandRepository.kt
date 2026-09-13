package com.afrithecus.brainbox.api.cbcratings.repository

import com.afrithecus.brainbox.api.cbcratings.entity.CbcStrandEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcStrandRepository : JpaRepository<CbcStrandEntity, UUID> {

    fun findByCode(code: String): CbcStrandEntity?

    fun findAllByOrderBySortOrderAsc(): List<CbcStrandEntity>

    fun findAllByCodeIn(codes: Collection<String>): List<CbcStrandEntity>
}
