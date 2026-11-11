package com.afrithecus.brainbox.api.curriculum.repository

import com.afrithecus.brainbox.api.curriculum.entity.SubjectEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SubjectRepository : JpaRepository<SubjectEntity, UUID> {

    fun findAllByIsActiveTrueOrderBySortOrderAscNameAsc(): List<SubjectEntity>

    fun findByNameIgnoreCase(name: String): SubjectEntity?
}
