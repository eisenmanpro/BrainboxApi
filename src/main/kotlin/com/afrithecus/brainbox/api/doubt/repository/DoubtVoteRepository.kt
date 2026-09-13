package com.afrithecus.brainbox.api.doubt.repository

import com.afrithecus.brainbox.api.doubt.entity.DoubtVoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DoubtVoteRepository : JpaRepository<DoubtVoteEntity, UUID> {

    fun findByVoterIdAndTargetTypeAndTargetId(voterId: UUID, targetType: String, targetId: UUID): DoubtVoteEntity?

    fun countByTargetTypeAndTargetIdAndDirection(targetType: String, targetId: UUID, direction: Int): Long
}
