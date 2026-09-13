package com.afrithecus.brainbox.api.feedback.repository

import com.afrithecus.brainbox.api.feedback.entity.FeedbackTemplateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FeedbackTemplateRepository : JpaRepository<FeedbackTemplateEntity, UUID> {

    fun findByClientId(clientId: String): FeedbackTemplateEntity?

    fun findAllByTeacherIdOrderByCreatedAtDesc(teacherId: UUID): List<FeedbackTemplateEntity>
}
