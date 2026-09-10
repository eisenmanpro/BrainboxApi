package com.afrithecus.brainbox.api.live.repository

import com.afrithecus.brainbox.api.live.entity.LivePollEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LivePollRepository : JpaRepository<LivePollEntity, UUID> {

    fun findAllByClassIdOrderByCreatedAtAsc(classId: UUID): List<LivePollEntity>
}
