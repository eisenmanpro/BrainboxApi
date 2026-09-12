package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupPollVoteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ClassGroupPollVoteRepository : JpaRepository<ClassGroupPollVoteEntity, UUID> {

    fun findByPollIdAndUserId(pollId: UUID, userId: UUID): ClassGroupPollVoteEntity?

    fun findAllByPollId(pollId: UUID): List<ClassGroupPollVoteEntity>
}
