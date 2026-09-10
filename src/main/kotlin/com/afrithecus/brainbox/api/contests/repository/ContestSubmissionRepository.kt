package com.afrithecus.brainbox.api.contests.repository

import com.afrithecus.brainbox.api.contests.entity.ContestSubmissionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ContestSubmissionRepository : JpaRepository<ContestSubmissionEntity, UUID> {

    fun findByUserIdAndContestId(userId: UUID, contestId: UUID): ContestSubmissionEntity?

    fun findAllByContestId(contestId: UUID): List<ContestSubmissionEntity>

    fun findAllByUserId(userId: UUID): List<ContestSubmissionEntity>

    /** Students who beat this score; +1 gives the user's rank among participants. */
    @Query(
        """
        SELECT COUNT(s) FROM ContestSubmissionEntity s
        WHERE s.contestId = :contestId AND s.score > :score
        """
    )
    fun countStrictlyBetter(@Param("contestId") contestId: UUID, @Param("score") score: Int): Long
}
