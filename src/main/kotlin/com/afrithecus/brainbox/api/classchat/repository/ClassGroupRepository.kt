package com.afrithecus.brainbox.api.classchat.repository

import com.afrithecus.brainbox.api.classchat.entity.ClassGroupEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ClassGroupRepository : JpaRepository<ClassGroupEntity, UUID> {

    fun findAllByTeacherIdOrderByUpdatedAtDesc(teacherId: UUID): List<ClassGroupEntity>

    fun findAllByClassId(classId: UUID): List<ClassGroupEntity>
}
