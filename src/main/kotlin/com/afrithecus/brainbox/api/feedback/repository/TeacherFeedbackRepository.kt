package com.afrithecus.brainbox.api.feedback.repository

import com.afrithecus.brainbox.api.feedback.entity.TeacherFeedbackEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherFeedbackRepository : JpaRepository<TeacherFeedbackEntity, UUID> {

    fun findByClientId(clientId: String): TeacherFeedbackEntity?

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<TeacherFeedbackEntity>

    fun findAllByTeacherIdAndStudentIdOrderByCreatedAtDesc(
        teacherId: UUID,
        studentId: UUID,
    ): List<TeacherFeedbackEntity>

    fun findAllByStudentIdOrderByCreatedAtDesc(studentId: UUID): List<TeacherFeedbackEntity>
}
