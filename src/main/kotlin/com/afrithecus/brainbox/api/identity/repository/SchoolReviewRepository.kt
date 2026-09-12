package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolReviewEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolReviewRepository : JpaRepository<SchoolReviewEntity, UUID> {

    fun findAllBySchoolIdOrderByCreatedAtDesc(schoolId: UUID): List<SchoolReviewEntity>

    fun countBySchoolId(schoolId: UUID): Long
}
