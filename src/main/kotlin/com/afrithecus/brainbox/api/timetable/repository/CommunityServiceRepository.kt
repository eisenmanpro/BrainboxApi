package com.afrithecus.brainbox.api.timetable.repository

import com.afrithecus.brainbox.api.timetable.entity.CommunityServiceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CommunityServiceRepository : JpaRepository<CommunityServiceEntity, UUID> {

    fun findByClientId(clientId: String): CommunityServiceEntity?

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<CommunityServiceEntity>
}
