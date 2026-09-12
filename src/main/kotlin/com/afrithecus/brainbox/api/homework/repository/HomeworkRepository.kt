package com.afrithecus.brainbox.api.homework.repository

import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import org.springframework.data.jpa.repository.JpaRepository

interface HomeworkRepository : JpaRepository<HomeworkEntity, String> {

    fun findAllByTeacherIdAndIsActiveTrueOrderByDueDateAsc(teacherId: java.util.UUID): List<HomeworkEntity>

    fun findAllByIsDraftFalseAndIsActiveTrue(): List<HomeworkEntity>

    fun findAllByClassIdOrderByDueDateDesc(classId: java.util.UUID): List<HomeworkEntity>
}
