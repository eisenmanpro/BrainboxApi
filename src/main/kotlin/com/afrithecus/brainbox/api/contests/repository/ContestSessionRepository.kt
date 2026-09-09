package com.afrithecus.brainbox.api.contests.repository

import com.afrithecus.brainbox.api.contests.entity.ContestSessionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContestSessionRepository : JpaRepository<ContestSessionEntity, UUID> {

    fun findByUserIdAndContestId(userId: UUID, contestId: UUID): ContestSessionEntity?
}
