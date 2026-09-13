package com.afrithecus.brainbox.api.contract.repository

import com.afrithecus.brainbox.api.contract.entity.LearningContractEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LearningContractRepository : JpaRepository<LearningContractEntity, UUID> {

    fun findByClientId(clientId: String): LearningContractEntity?

    fun findAllByTeacherIdOrderByLastUpdatedDesc(teacherId: UUID): List<LearningContractEntity>

    fun findAllByChildIdOrderByLastUpdatedDesc(childId: UUID): List<LearningContractEntity>
}
