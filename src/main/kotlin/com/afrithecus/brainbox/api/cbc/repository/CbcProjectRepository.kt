package com.afrithecus.brainbox.api.cbc.repository

import com.afrithecus.brainbox.api.cbc.entity.CbcProjectEntity
import com.afrithecus.brainbox.api.cbc.model.ProjectStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CbcProjectRepository : JpaRepository<CbcProjectEntity, UUID> {

    fun findAllByStatusOrderByCreatedAtDesc(status: ProjectStatus): List<CbcProjectEntity>

    fun findAllByStudentIdOrderByCreatedAtDesc(studentId: UUID): List<CbcProjectEntity>
}
