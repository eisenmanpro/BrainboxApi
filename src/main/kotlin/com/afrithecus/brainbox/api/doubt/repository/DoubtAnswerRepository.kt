package com.afrithecus.brainbox.api.doubt.repository

import com.afrithecus.brainbox.api.doubt.entity.DoubtAnswerEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DoubtAnswerRepository : JpaRepository<DoubtAnswerEntity, UUID> {

    fun findAllByQuestionId(questionId: UUID): List<DoubtAnswerEntity>

    fun countByQuestionId(questionId: UUID): Long
}
