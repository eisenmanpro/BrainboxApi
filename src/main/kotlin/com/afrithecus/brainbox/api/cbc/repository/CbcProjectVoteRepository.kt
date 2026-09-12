package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectVoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectVoteRepository : JpaRepository<CbcProjectVoteEntity, UUID> {

    fun findByProjectIdAndVoterKey(projectId: UUID, voterKey: String): CbcProjectVoteEntity?

    fun findAllByVoterKeyAndProjectIdIn(voterKey: String, projectIds: Collection<UUID>): List<CbcProjectVoteEntity>
}
