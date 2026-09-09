package com.afrithecus.brainbox.api.classes.repository

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherClassRepository : JpaRepository<TeacherClassEntity, UUID> {

    fun findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacherUserId: UUID): List<TeacherClassEntity>
}
