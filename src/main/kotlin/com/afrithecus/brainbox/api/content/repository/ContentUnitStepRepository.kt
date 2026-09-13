package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContentUnitStepRepository : JpaRepository<ContentUnitStepEntity, UUID> {

    fun findAllByUnitIdOrderByOrderIndexAsc(unitId: UUID): List<ContentUnitStepEntity>
}
