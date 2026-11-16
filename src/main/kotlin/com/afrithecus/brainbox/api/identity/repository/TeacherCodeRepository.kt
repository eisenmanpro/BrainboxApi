package com.afrithecus.brainbox.api.identity.repository

import com.afrithecus.brainbox.api.identity.entity.TeacherCodeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherCodeRepository : JpaRepository<TeacherCodeEntity, UUID> {

    fun findByCodeAndActiveTrue(code: String): TeacherCodeEntity?

    fun findByTeacherUserIdAndActiveTrue(teacherUserId: UUID): TeacherCodeEntity?

    /** Batch read for the admin teacher lists (one code per teacher at most). */
    fun findAllByTeacherUserIdInAndActiveTrue(teacherUserIds: Collection<UUID>): List<TeacherCodeEntity>

    fun existsByCode(code: String): Boolean

    fun existsByTeacherUserId(teacherUserId: UUID): Boolean
}
