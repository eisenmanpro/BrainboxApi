package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectTrackEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectTrackRepository : JpaRepository<CbcProjectTrackEntity, UUID> {

    fun findByProjectIdAndEmail(projectId: UUID, email: String): CbcProjectTrackEntity?
}
