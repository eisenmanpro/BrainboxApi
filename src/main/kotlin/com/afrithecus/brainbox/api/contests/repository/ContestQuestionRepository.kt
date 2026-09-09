package com.afrithecus.brainbox.api.contests.repository

import com.afrithecus.brainbox.api.contests.entity.ContestQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContestQuestionRepository : JpaRepository<ContestQuestionEntity, UUID> {

    fun findAllByContestIdOrderByOrderIndexAsc(contestId: UUID): List<ContestQuestionEntity>

    fun countByContestId(contestId: UUID): Long
}
