package com.afrithecus.brainbox.api.attendance.repository

import com.afrithecus.brainbox.api.attendance.entity.SchoolSmsBudgetEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolSmsBudgetRepository : JpaRepository<SchoolSmsBudgetEntity, UUID> {

    fun findBySchoolIdAndTermLabel(schoolId: UUID, termLabel: String): SchoolSmsBudgetEntity?

    fun findAllByTermLabel(termLabel: String): List<SchoolSmsBudgetEntity>
}
