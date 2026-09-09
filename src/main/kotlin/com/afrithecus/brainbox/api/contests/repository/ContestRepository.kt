package com.afrithecus.brainbox.api.contests.repository

import com.afrithecus.brainbox.api.contests.entity.ContestEntity
import com.afrithecus.brainbox.api.contests.model.ContestLifecycle
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContestRepository : JpaRepository<ContestEntity, UUID> {

    fun findAllByLifecycle(lifecycle: ContestLifecycle): List<ContestEntity>
}
