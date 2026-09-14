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

    /** Country-level lookup used by the concept_lookup MCP tool (grade/strand filtered in memory). */
    fun findAllByCountryCodeAndCurriculum(countryCode: String, curriculum: String): List<CurriculumMapEntity>

    /** All curriculum mappings for a concept, used by Phase 7.4b validation. */
    fun findAllByConceptIdOrderBySortOrderAsc(conceptId: UUID): List<CurriculumMapEntity>

    /** The first national mapping for a concept, used by the Phase 7.3 projection. */
    fun findFirstByConceptIdAndCountryCodeAndCurriculumOrderBySortOrderAsc(
        conceptId: UUID,
        countryCode: String,
        curriculum: String,
    ): CurriculumMapEntity?
}