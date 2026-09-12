package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalSubjectConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TraditionalSubjectConfigRepository : JpaRepository<TraditionalSubjectConfigEntity, UUID> {

    fun findAllBySchoolIdAndGradeLevelOrderByOrderIndexAsc(schoolId: UUID, gradeLevel: String): List<TraditionalSubjectConfigEntity>

    fun findAllByGradeLevelOrderByOrderIndexAsc(gradeLevel: String): List<TraditionalSubjectConfigEntity>

    @Modifying
    @Query("DELETE FROM TraditionalSubjectConfigEntity c WHERE c.gradeLevel = :gradeLevel AND (:schoolId IS NULL OR c.schoolId = :schoolId)")
    fun deleteAllByScope(@Param("schoolId") schoolId: UUID?, @Param("gradeLevel") gradeLevel: String)
}
