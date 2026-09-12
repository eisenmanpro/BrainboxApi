package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolJoinRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolJoinRequestRepository : JpaRepository<SchoolJoinRequestEntity, UUID> {

    fun findBySchoolIdAndStudentIdAndStatus(schoolId: UUID, studentId: UUID, status: String): SchoolJoinRequestEntity?
}
