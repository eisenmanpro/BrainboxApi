package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalExamRepository : JpaRepository<TraditionalExamEntity, UUID> {

    fun findAllBySchoolIdOrderByCreatedAtDesc(schoolId: UUID): List<TraditionalExamEntity>

    fun findAllBySchoolIdAndGradeLevelOrderByCreatedAtDesc(schoolId: UUID, gradeLevel: String): List<TraditionalExamEntity>

    fun findAllByGradeLevelOrderByCreatedAtDesc(gradeLevel: String): List<TraditionalExamEntity>

    fun findAllByStatusOrderByPublishedAtDesc(status: TraditionalExamStatus): List<TraditionalExamEntity>

    /** Resolves a client-assigned exam id within a school (multi-tenant lookups). */
    fun findAllByClientExamId(clientExamId: String): List<TraditionalExamEntity>

    fun findBySchoolIdAndClientExamId(schoolId: UUID?, clientExamId: String): TraditionalExamEntity?
}
