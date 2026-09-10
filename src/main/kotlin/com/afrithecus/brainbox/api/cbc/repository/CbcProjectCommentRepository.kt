package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectCommentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectCommentRepository : JpaRepository<CbcProjectCommentEntity, UUID> {

    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: UUID): List<CbcProjectCommentEntity>

    fun findByProjectIdAndId(projectId: UUID, id: UUID): CbcProjectCommentEntity?
}
