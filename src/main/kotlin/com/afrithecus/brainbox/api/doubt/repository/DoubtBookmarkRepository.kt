package com.afrithecus.brainbox.api.doubt.repository

import com.afrithecus.brainbox.api.doubt.entity.DoubtBookmarkEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DoubtBookmarkRepository : JpaRepository<DoubtBookmarkEntity, UUID> {

    fun existsByUserIdAndQuestionId(userId: UUID, questionId: UUID): Boolean

    fun findByUserIdAndQuestionId(userId: UUID, questionId: UUID): DoubtBookmarkEntity?

    fun deleteByUserIdAndQuestionId(userId: UUID, questionId: UUID)
}
