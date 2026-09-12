package com.afrithecus.brainbox.api.exams.repository

import com.afrithecus.brainbox.api.exams.entity.TeacherQuestionBankEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TeacherQuestionBankRepository : JpaRepository<TeacherQuestionBankEntity, UUID> {

    fun findByClientId(clientId: String): TeacherQuestionBankEntity?

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<TeacherQuestionBankEntity>
}
