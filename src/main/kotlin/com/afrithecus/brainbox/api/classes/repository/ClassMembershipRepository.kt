package com.afrithecus.brainbox.api.classes.repository

import com.afrithecus.brainbox.api.classes.entity.ClassMembershipEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ClassMembershipRepository : JpaRepository<ClassMembershipEntity, UUID> {

    fun findByClassIdAndStudentId(classId: UUID, studentId: UUID): ClassMembershipEntity?

    fun findAllByClassId(classId: UUID): List<ClassMembershipEntity>

    fun countByClassId(classId: UUID): Long

    fun findAllByStudentId(studentId: UUID): List<ClassMembershipEntity>

    fun deleteByClassIdAndStudentId(classId: UUID, studentId: UUID)
}
