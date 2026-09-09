package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherCodeRepository : JpaRepository<TeacherCodeEntity, UUID> {

    fun findByCodeAndActiveTrue(code: String): TeacherCodeEntity?

    fun existsByTeacherUserId(teacherUserId: UUID): Boolean
}
