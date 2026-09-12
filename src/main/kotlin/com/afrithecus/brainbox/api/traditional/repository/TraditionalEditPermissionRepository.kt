package com.afrithecus.brainbox.api.traditional.repository

import com.afrithecus.brainbox.api.traditional.entity.TraditionalEditPermissionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TraditionalEditPermissionRepository : JpaRepository<TraditionalEditPermissionEntity, UUID> {

    fun findAllByExamIdAndStudentIdAndTeacherIdOrderByGrantedAtDesc(examId: UUID, studentId: UUID, teacherId: UUID): List<TraditionalEditPermissionEntity>
}
