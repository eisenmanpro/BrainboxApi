package com.afrithecus.brainbox.api.classes.repository

import com.afrithecus.brainbox.api.classes.entity.StudentClassTransitionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StudentClassTransitionRepository : JpaRepository<StudentClassTransitionEntity, UUID> {

    fun findAllByStudentIdOrderByCreatedAtDesc(studentId: UUID): List<StudentClassTransitionEntity>
}
