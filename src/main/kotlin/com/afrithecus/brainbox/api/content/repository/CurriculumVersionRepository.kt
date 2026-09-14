package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.CurriculumVersionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CurriculumVersionRepository : JpaRepository<CurriculumVersionEntity, UUID> {

    fun findByCountryCodeAndCurriculumAndCurriculumVersion(
        countryCode: String,
        curriculum: String,
        curriculumVersion: String,
    ): CurriculumVersionEntity?
}
