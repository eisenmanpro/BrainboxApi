package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LivePollVoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LivePollVoteRepository : JpaRepository<LivePollVoteEntity, UUID> {

    fun findByPollIdAndUserId(pollId: UUID, userId: UUID): LivePollVoteEntity?

    fun findAllByPollId(pollId: UUID): List<LivePollVoteEntity>
}
