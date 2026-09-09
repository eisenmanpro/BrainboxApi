package com.afrithecus.brainbox.api.exams.admin

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSubmissionRepository
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.exams.web.ExamSummary
import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import com.afrithecus.brainbox.api.identity.repository.SchoolRepository
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Authoring/content administration for exams (used by ADMIN now; TEACHER flows later). */
@Service
class ExamAuthoringService(
    private val examRepository: ExamRepository,
    private val questionRepository: ExamQuestionRepository,
    private val submissionRepository: ExamSubmissionRepository,
    private val schoolRepository: SchoolRepository,
    private val userRepository: UserRepository,
    private val codec: QuestionCodec,
) {

    @Transactional
    fun create(request: CreateExamRequest, authorUserId: UUID): ExamDetail {
        val examType = parseEnum<ExamType>(request.examType, "examType")
        val scope = parseEnum<ExamScope>(request.scope, "scope")
        val schoolId = request.schoolId?.takeIf { it.isNotBlank() }?.let { raw ->
            val id = runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw invalidArgument("schoolId is not a valid identifier")
            schoolRepository.findById(id).orElse(null)?.id
                ?: throw notFound("School not found")
        }
        if (scope != ExamScope.GLOBAL && schoolId == null) {
            throw invalidArgument("scope " + scope.name + " requires a schoolId")
        }

        val author = userRepository.findById(authorUserId).orElse(null)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "Author not found")

        val exam = ExamEntity().apply {
            title = request.title.trim()
            subject = request.subject.trim()
            this.examType = examType
            this.scope = scope
            this.schoolId = schoolId
            durationMinutes = request.durationMinutes
            difficulty = request.difficulty
            status = if (request.isPublished) ExamStatus.PUBLISHED else ExamStatus.DRAFT
            examYear = request.examYear
            isMcp = request.isMcp
            coverImageUrl = request.coverImageUrl?.trim()?.takeIf { it.isNotEmpty() }
            createdBy = author.id
        }
        examRepository.save(exam)

        val questions = request.questions.mapIndexed { index, q ->
            val qType = parseEnum<QuestionType>(q.type, "type")
            ExamQuestionEntity().apply {
                examId = exam.id
                text = q.text
                this.qType = qType
                options = codec.toJson(q.options)
                correctAnswer = q.correctAnswer
                explanation = q.explanation
                points = q.points
                difficulty = q.difficulty
                matchingPairs = codec.toJson(q.matchingPairs)
                topic = q.topic?.trim()?.takeIf { it.isNotEmpty() }
                subtopic = q.subtopic?.trim()?.takeIf { it.isNotEmpty() }
                orderIndex = index
            }
        }
        questionRepository.saveAll(questions)
        exam.questionCount = questions.size
        examRepository.save(exam)
        return toDetail(exam, questions)
    }

    @Transactional(readOnly = true)
    fun list(): List<ExamSummary> = examRepository.findAll().map { toSummary(it) }

    @Transactional(readOnly = true)
    fun get(examIdRaw: String): ExamDetail {
        val exam = findExam(examIdRaw)
        return toDetail(exam, questionRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id))
    }

    @Transactional
    fun archive(examIdRaw: String) {
        val exam = findExam(examIdRaw)
        exam.status = ExamStatus.ARCHIVED
        examRepository.save(exam)
    }

    @Transactional
    fun publish(examIdRaw: String) {
        val exam = findExam(examIdRaw)
        if (exam.questionCount == 0) throw conflict("Cannot publish an exam without questions")
        exam.status = ExamStatus.PUBLISHED
        examRepository.save(exam)
    }

    private fun findExam(raw: String): ExamEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("exam id is not a valid identifier")
        return examRepository.findById(id).orElseThrow { notFound("Exam not found") }
    }

    private fun toSummary(exam: ExamEntity): ExamSummary = ExamSummary(
        id = exam.id.toString(),
        title = exam.title,
        subject = exam.subject,
        durationMinutes = exam.durationMinutes,
        questionCount = exam.questionCount,
        difficulty = exam.difficulty,
        status = exam.status.name,
        isPastPaper = exam.examType == ExamType.PAST_PAPER,
        examYear = exam.examYear,
        coverImageUrl = exam.coverImageUrl,
        averageScore = averageScore(exam.id),
        studentsTaken = submissionRepository.findAllByExamId(exam.id).size,
    )

    /** Full detail including key material - authoring/admin context only. */
    private fun toDetail(exam: ExamEntity, questions: List<ExamQuestionEntity>): ExamDetail =
        ExamDetail(
            id = exam.id.toString(),
            title = exam.title,
            subject = exam.subject,
            durationMinutes = exam.durationMinutes,
            questionCount = exam.questionCount,
            difficulty = exam.difficulty,
            status = exam.status.name,
            questions = questions.map { toQuestionPayload(it, includeKeys = true) },
            createdBy = exam.createdBy.toString(),
            createdAt = exam.createdAt.toEpochMilli(),
            isPublished = exam.status == ExamStatus.PUBLISHED,
            averageScore = averageScore(exam.id) ?: 0,
            studentsTaken = submissionRepository.findAllByExamId(exam.id).size,
        )

    internal fun toQuestionPayload(question: ExamQuestionEntity, includeKeys: Boolean): QuestionPayload =
        QuestionPayload(
            id = question.id.toString(),
            text = question.text,
            type = question.qType.name,
            options = codec.parseList(question.options),
            correctAnswer = if (includeKeys) question.correctAnswer else null,
            explanation = if (includeKeys) question.explanation else null,
            points = question.points,
            difficulty = question.difficulty,
            matchingPairs = if (includeKeys) codec.parseMap(question.matchingPairs) else null,
            topic = question.topic,
            subtopic = question.subtopic,
        )

    private fun averageScore(examId: UUID): Int? {
        val submissions = submissionRepository.findAllByExamId(examId)
        if (submissions.isEmpty()) return null
        return submissions.map { it.percentage }.average().toInt()
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
        runCatching { enumValueOf<T>(value.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument(field + " has an invalid value")

    internal fun questionsOf(examId: UUID): List<ExamQuestionEntity> =
        questionRepository.findAllByExamIdOrderByOrderIndexAsc(examId)
}
