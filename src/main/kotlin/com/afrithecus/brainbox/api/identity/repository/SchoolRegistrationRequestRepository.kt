package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.SchoolRegistrationRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SchoolRegistrationRequestRepository : JpaRepository<SchoolRegistrationRequestEntity, UUID> {

    fun findByRequestId(requestId: String): SchoolRegistrationRequestEntity?

    fun findBySchoolNameIgnoreCaseAndStatus(schoolName: String, status: String): SchoolRegistrationRequestEntity?

    fun findAllByStatusOrderByCreatedAtDesc(status: String): List<SchoolRegistrationRequestEntity>

    fun findAllByOrderByCreatedAtDesc(): List<SchoolRegistrationRequestEntity>
}
