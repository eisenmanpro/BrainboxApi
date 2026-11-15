package com.afrithecus.brainbox.api.classes.repository

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherClassRepository : JpaRepository<TeacherClassEntity, UUID> {

    fun findAllByTeacherUserIdAndIsActiveTrueOrderByNameAsc(teacherUserId: UUID): List<TeacherClassEntity>

    fun findAllBySchoolIdAndIsActiveTrueOrderByNameAsc(schoolId: UUID): List<TeacherClassEntity>

    /** Legacy rows with no school scope; used when the exam has no school id. */
    fun findAllByGradeLevelAndIsActiveTrueOrderByNameAsc(gradeLevel: String): List<TeacherClassEntity>
}
