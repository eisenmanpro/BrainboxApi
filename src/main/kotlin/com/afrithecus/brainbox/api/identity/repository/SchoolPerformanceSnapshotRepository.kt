package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolPerformanceSnapshotEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolPerformanceSnapshotRepository : JpaRepository<SchoolPerformanceSnapshotEntity, UUID> {

    fun findBySchoolIdAndTermAndSnapshotYear(schoolId: UUID, term: String, snapshotYear: Int): SchoolPerformanceSnapshotEntity?

    fun findAllBySchoolIdOrderBySnapshotYearDescTermDesc(schoolId: UUID): List<SchoolPerformanceSnapshotEntity>
}
