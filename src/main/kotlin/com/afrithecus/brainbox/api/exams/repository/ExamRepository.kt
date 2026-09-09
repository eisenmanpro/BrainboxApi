package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExamRepository : JpaRepository<ExamEntity, UUID> {

    fun findAllByStatus(status: ExamStatus): List<ExamEntity>

    fun findAllByStatusAndSubjectIgnoreCase(status: ExamStatus, subject: String): List<ExamEntity>
}
