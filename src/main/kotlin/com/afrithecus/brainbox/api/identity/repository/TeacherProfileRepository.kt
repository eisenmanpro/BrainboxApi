package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.TeacherProfileEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherProfileRepository : JpaRepository<TeacherProfileEntity, UUID> {

    fun findByUserId(userId: UUID): TeacherProfileEntity?

    fun findAllBySchoolId(schoolId: UUID): List<TeacherProfileEntity>

    fun deleteByUserId(userId: UUID)
}
