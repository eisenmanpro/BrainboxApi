package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContentUnitQuestionRepository : JpaRepository<ContentUnitQuestionEntity, UUID> {

    fun findAllByUnitIdOrderByOrderIndexAsc(unitId: UUID): List<ContentUnitQuestionEntity>
}
