package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectViewEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectViewRepository : JpaRepository<CbcProjectViewEntity, UUID> {

    fun findByProjectIdAndViewerKey(projectId: UUID, viewerKey: String): CbcProjectViewEntity?
}
