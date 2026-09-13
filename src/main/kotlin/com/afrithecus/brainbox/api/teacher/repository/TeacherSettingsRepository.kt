package com.afrithecus.brainbox.api.teacher.repository

import com.afrithecus.brainbox.api.teacher.entity.TeacherSettingsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherSettingsRepository : JpaRepository<TeacherSettingsEntity, UUID> {

    fun findByTeacherId(teacherId: UUID): TeacherSettingsEntity?
}
