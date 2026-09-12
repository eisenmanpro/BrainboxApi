package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamEntity
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.traditional.model.TraditionalExamStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalExamRepository : JpaRepository<TraditionalExamEntity, UUID> {

    fun findAllBySchoolIdOrderByCreatedAtDesc(schoolId: UUID): List<TraditionalExamEntity>

    fun findAllBySchoolIdAndGradeLevelOrderByCreatedAtDesc(schoolId: UUID, gradeLevel: String): List<TraditionalExamEntity>

    fun findAllByGradeLevelOrderByCreatedAtDesc(gradeLevel: String): List<TraditionalExamEntity>

    fun findAllByStatusOrderByPublishedAtDesc(status: TraditionalExamStatus): List<TraditionalExamEntity>

    fun findAllBySchoolIdAndGradeLevelAndTermAndYear(schoolId: UUID, gradeLevel: String, term: ExamTerm, year: Int): List<TraditionalExamEntity>
}
