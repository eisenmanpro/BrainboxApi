package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectVoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectVoteRepository : JpaRepository<CbcProjectVoteEntity, UUID> {

    fun findByProjectIdAndUserId(projectId: UUID, userId: UUID): CbcProjectVoteEntity?

    fun findAllByProjectId(projectId: UUID): List<CbcProjectVoteEntity>

    fun findAllByUserIdAndProjectIdIn(userId: UUID, projectIds: Collection<UUID>): List<CbcProjectVoteEntity>
}
