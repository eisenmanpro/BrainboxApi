package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalMarkEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalMarkRepository : JpaRepository<TraditionalMarkEntity, UUID> {

    fun findAllByExamId(examId: UUID): List<TraditionalMarkEntity>

    fun findAllByExamIdAndSubjectId(examId: UUID, subjectId: String): List<TraditionalMarkEntity>

    fun findAllByExamIdAndStudentId(examId: UUID, studentId: UUID): List<TraditionalMarkEntity>

    fun findAllByExamIdAndStudentIdIn(examId: UUID, studentIds: Collection<UUID>): List<TraditionalMarkEntity>

    fun findAllByStudentId(studentId: UUID): List<TraditionalMarkEntity>

    fun findByExamIdAndStudentIdAndSubjectId(examId: UUID, studentId: UUID, subjectId: String): TraditionalMarkEntity?

    fun countByExamId(examId: UUID): Long
}
