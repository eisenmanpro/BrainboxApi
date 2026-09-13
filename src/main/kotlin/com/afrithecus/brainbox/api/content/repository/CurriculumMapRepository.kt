package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CurriculumMapRepository : JpaRepository<CurriculumMapEntity, UUID> {

    fun findAllByCountryCodeAndCurriculumAndGradeLevelOrderBySortOrderAsc(
        countryCode: String,
        curriculum: String,
        gradeLevel: String,
    ): List<CurriculumMapEntity>
}
