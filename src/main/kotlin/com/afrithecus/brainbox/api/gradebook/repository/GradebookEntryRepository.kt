package com.afrithecus.brainbox.api.gradebook.repository

import com.afrithecus.brainbox.api.gradebook.entity.GradebookEntryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface GradebookEntryRepository : JpaRepository<GradebookEntryEntity, UUID> {

    fun findByClientId(clientId: String): GradebookEntryEntity?

    fun findAllByClassId(classId: UUID): List<GradebookEntryEntity>

    fun findByClassIdAndAssessmentIdAndStudentId(classId: UUID, assessmentId: String, studentId: UUID): GradebookEntryEntity?

    fun findAllByStudentId(studentId: UUID): List<GradebookEntryEntity>

    fun findAllByStudentIdIn(studentIds: Collection<UUID>): List<GradebookEntryEntity>

    @Modifying
    @Query("DELETE FROM GradebookEntryEntity e WHERE e.assessmentId = :assessmentId")
    fun deleteAllByAssessmentId(@Param("assessmentId") assessmentId: String)
}
