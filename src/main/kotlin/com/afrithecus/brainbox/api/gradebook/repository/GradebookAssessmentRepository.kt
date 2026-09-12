package com.afrithecus.brainbox.api.gradebook.repository

import com.afrithecus.brainbox.api.gradebook.entity.GradebookAssessmentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GradebookAssessmentRepository : JpaRepository<GradebookAssessmentEntity, UUID> {

    fun findByClientId(clientId: String): GradebookAssessmentEntity?

    fun findAllByClassIdOrderByDateAssignedDesc(classId: UUID): List<GradebookAssessmentEntity>

    fun findAllByClassIdAndClientIdIn(classId: UUID, clientIds: Collection<String>): List<GradebookAssessmentEntity>

    fun findAllByClientIdIn(clientIds: Collection<String>): List<GradebookAssessmentEntity>
}
