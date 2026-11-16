package com.afrithecus.brainbox.api.teacher.repository

import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherSettingsRepository : JpaRepository<TeacherSettingsEntity, UUID> {

    fun findByTeacherId(teacherId: UUID): TeacherSettingsEntity?

    /** Batch read for the admin teacher lists (avoids one query per row). */
    fun findAllByTeacherIdIn(teacherIds: Collection<UUID>): List<TeacherSettingsEntity>
}
