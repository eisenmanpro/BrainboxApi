package com.afrithecus.brainbox.api.feedback

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.feedback.entity.FeedbackTemplateEntity
import com.afrithecus.brainbox.api.feedback.entity.TeacherFeedbackEntity
import com.afrithecus.brainbox.api.feedback.repository.FeedbackTemplateRepository
import com.afrithecus.brainbox.api.feedback.repository.TeacherFeedbackRepository
import com.afrithecus.brainbox.api.feedback.web.FeedbackTemplatePayload
import com.afrithecus.brainbox.api.feedback.web.TeacherFeedbackPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Teacher feedback (doc 04 section 7): reusable templates, the feedback history
 * and single/bulk submission. Writes are idempotent on the client-supplied id
 * because the app replays them from its teacher-mutations outbox.
 */
@Service
class FeedbackService(
    private val feedbackRepository: TeacherFeedbackRepository,
    private val templateRepository: FeedbackTemplateRepository,
    private val codec: QuestionCodec,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun templates(teacher: UserEntity): List<FeedbackTemplatePayload> {
        requireTeacher(teacher)
        return templateRepository.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id).map(::templatePayload)
    }

    @Transactional
    fun saveTemplate(teacher: UserEntity, request: FeedbackTemplatePayload): FeedbackTemplatePayload {
        requireTeacher(teacher)
        val title = request.title.trim()
        if (title.isEmpty()) throw invalidArgument("Template title is required")
        val content = request.content.trim()
        if (content.isEmpty()) throw invalidArgument("Template content is required")
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "ft_" + UUID.randomUUID()
        val existing = templateRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) throw forbidden("Not your template")
        val entity = existing ?: FeedbackTemplateEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        entity.title = title
        entity.content = content
        entity.category = request.category.trim().ifEmpty { "GENERAL" }
        templateRepository.saveAndFlush(entity)
        return templatePayload(entity)
    }

    @Transactional
    fun deleteTemplate(teacher: UserEntity, templateIdRaw: String) {
        requireTeacher(teacher)
        val entity = resolveTemplate(teacher, templateIdRaw) ?: return
        templateRepository.delete(entity)
    }

    @Transactional(readOnly = true)
    fun history(teacher: UserEntity, studentIdRaw: String?): List<TeacherFeedbackPayload> {
        requireTeacher(teacher)
        val rows = if (studentIdRaw.isNullOrBlank()) {
            feedbackRepository.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id)
        } else {
            feedbackRepository.findAllByTeacherIdAndStudentIdOrderByCreatedAtDesc(
                teacher.id,
                parseUuid(studentIdRaw, "studentId"),
            )
        }
        return rows.map(::feedbackPayload)
    }

    @Transactional
    fun submit(teacher: UserEntity, request: TeacherFeedbackPayload): TeacherFeedbackPayload {
        requireTeacher(teacher)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "fb_" + UUID.randomUUID()
        val existing = feedbackRepository.findByClientId(clientId)
        if (existing != null && existing.teacherId != teacher.id) throw forbidden("Not your feedback")
        val entity = existing ?: TeacherFeedbackEntity().apply {
            this.clientId = clientId
            teacherId = teacher.id
        }
        entity.studentId = parseUuid(request.studentId, "studentId")
        entity.submissionId = request.submissionId.trim().takeIf { it.isNotEmpty() }
        entity.textFeedback = request.textFeedback?.trim()?.takeIf { it.isNotEmpty() }
        entity.voiceFeedbackUrl = request.voiceFeedbackUrl?.trim()?.takeIf { it.isNotEmpty() }
        entity.photoFeedbackUrls = codec.toJson(request.photoFeedbackUrls.filter { it.isNotBlank() })
        entity.rubricScores = codec.toJson(
            request.rubricScores.filter { it.key.isNotBlank() }.mapValues { it.value.toString() },
        )
        feedbackRepository.saveAndFlush(entity)
        return feedbackPayload(entity)
    }

    @Transactional
    fun submitBulk(teacher: UserEntity, requests: List<TeacherFeedbackPayload>): List<TeacherFeedbackPayload> =
        requests.map { submit(teacher, it) }

    // ------------------------------------------------------------ internals

    private fun resolveTemplate(teacher: UserEntity, raw: String): FeedbackTemplateEntity? {
        val byClient = templateRepository.findByClientId(raw)
        if (byClient != null) {
            if (byClient.teacherId != teacher.id) throw forbidden("Not your template")
            return byClient
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val entity = templateRepository.findById(id).orElse(null) ?: return null
        if (entity.teacherId != teacher.id) throw forbidden("Not your template")
        return entity
    }

    private fun templatePayload(entity: FeedbackTemplateEntity) = FeedbackTemplatePayload(
        id = entity.clientId,
        teacherId = entity.teacherId.toString(),
        title = entity.title,
        content = entity.content,
        category = entity.category,
    )

    private fun feedbackPayload(entity: TeacherFeedbackEntity) = TeacherFeedbackPayload(
        id = entity.clientId,
        submissionId = entity.submissionId.orEmpty(),
        teacherId = entity.teacherId.toString(),
        studentId = entity.studentId.toString(),
        textFeedback = entity.textFeedback,
        voiceFeedbackUrl = entity.voiceFeedbackUrl,
        photoFeedbackUrls = codec.parseList(entity.photoFeedbackUrls) ?: emptyList(),
        rubricScores = codec.parseMap(entity.rubricScores)
            ?.mapValues { it.value.toIntOrNull() ?: 0 }
            ?: emptyMap(),
        createdAt = entity.createdAt.toEpochMilli(),
    )

    private fun parseUuid(raw: String, field: String): UUID =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()
            ?: throw invalidArgument(field + " is not a valid identifier")

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw forbidden("Teacher access only")
    }

    private fun forbidden(message: String) = ApiException(ApiErrorCode.FORBIDDEN, message)
}
