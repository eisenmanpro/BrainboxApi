package com.afrithecus.brainbox.api.timetable.repository

import com.afrithecus.brainbox.api.timetable.entity.HouseGroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HouseGroupRepository : JpaRepository<HouseGroupEntity, UUID> {

    fun findByClientId(clientId: String): HouseGroupEntity?

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<HouseGroupEntity>
}
