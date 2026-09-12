package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalExamSubjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TraditionalExamSubjectRepository : JpaRepository<TraditionalExamSubjectEntity, UUID> {

    fun findAllByExamIdOrderByOrderIndexAsc(examId: UUID): List<TraditionalExamSubjectEntity>

    fun findByExamIdAndSubjectId(examId: UUID, subjectId: String): TraditionalExamSubjectEntity?

    @Modifying
    @Query("DELETE FROM TraditionalExamSubjectEntity s WHERE s.examId = :examId")
    fun deleteAllByExamId(@Param("examId") examId: UUID)
}
