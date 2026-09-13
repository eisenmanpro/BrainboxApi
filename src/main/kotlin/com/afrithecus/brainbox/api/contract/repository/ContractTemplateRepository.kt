package com.afrithecus.brainbox.api.contract.repository

import com.afrithecus.brainbox.api.contract.entity.ContractTemplateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContractTemplateRepository : JpaRepository<ContractTemplateEntity, UUID> {

    fun findAllByOrderByCategoryAscTitleAsc(): List<ContractTemplateEntity>
}
