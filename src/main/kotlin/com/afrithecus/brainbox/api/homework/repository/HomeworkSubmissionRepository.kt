package com.afrithecus.brainbox.api.homework.repository

import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HomeworkSubmissionRepository : JpaRepository<HomeworkSubmissionEntity, UUID> {

    fun findByHomeworkIdAndStudentId(homeworkId: String, studentId: UUID): HomeworkSubmissionEntity?

    fun findAllByHomeworkId(homeworkId: String): List<HomeworkSubmissionEntity>

    fun findAllByStudentId(studentId: UUID): List<HomeworkSubmissionEntity>
}
