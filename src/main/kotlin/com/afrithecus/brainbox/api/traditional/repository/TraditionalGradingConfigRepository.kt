package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalGradingConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalGradingConfigRepository : JpaRepository<TraditionalGradingConfigEntity, UUID> {

    fun findAllBySchoolIdAndGradeLevel(schoolId: UUID, gradeLevel: String): List<TraditionalGradingConfigEntity>

    fun findAllByGradeLevel(gradeLevel: String): List<TraditionalGradingConfigEntity>
}
