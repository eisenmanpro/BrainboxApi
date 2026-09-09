package com.afrithecus.brainbox.api.learning.repository

import com.afrithecus.brainbox.api.learning.entity.ReadingProgressEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReadingProgressRepository : JpaRepository<ReadingProgressEntity, UUID> {

    fun findByUserIdAndFileId(userId: UUID, fileId: UUID): ReadingProgressEntity?
}
