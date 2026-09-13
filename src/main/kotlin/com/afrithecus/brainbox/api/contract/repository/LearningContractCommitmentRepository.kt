package com.afrithecus.brainbox.api.contract.repository

import com.afrithecus.brainbox.api.contract.entity.LearningContractCommitmentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LearningContractCommitmentRepository : JpaRepository<LearningContractCommitmentEntity, UUID> {

    fun findAllByContractIdOrderBySortOrderAsc(contractId: UUID): List<LearningContractCommitmentEntity>

    fun findByContractIdAndClientId(contractId: UUID, clientId: String): LearningContractCommitmentEntity?
}
