package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ClassGroupRepository : JpaRepository<ClassGroupEntity, UUID> {

    fun findAllByTeacherIdOrderByUpdatedAtDesc(teacherId: UUID): List<ClassGroupEntity>

    fun findAllByClassId(classId: UUID): List<ClassGroupEntity>

    /** Every group of a school, newest activity first (admin moderation list). */
    fun findAllBySchoolIdOrderByUpdatedAtDesc(schoolId: UUID): List<ClassGroupEntity>

    /** Grade-scoped groups of one school, newest activity first. */
    fun findAllBySchoolIdAndScopeAndGradeLevelOrderByUpdatedAtDesc(
        schoolId: UUID,
        scope: String,
        gradeLevel: String,
    ): List<ClassGroupEntity>
}
