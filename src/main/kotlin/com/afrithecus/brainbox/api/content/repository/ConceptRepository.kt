package com.afrithecus.brainbox.api.content.repository

import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConceptRepository : JpaRepository<ConceptEntity, UUID> {

    fun findByCode(code: String): ConceptEntity?

    fun findAllBySubjectOrderBySortOrderAsc(subject: String): List<ConceptEntity>
}
