package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalConfirmationEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalConfirmationRepository : JpaRepository<TraditionalConfirmationEntity, UUID> {

    fun findAllByExamId(examId: UUID): List<TraditionalConfirmationEntity>

    fun findByExamIdAndTeacherIdAndGradeLevel(examId: UUID, teacherId: UUID, gradeLevel: String): TraditionalConfirmationEntity?
}
