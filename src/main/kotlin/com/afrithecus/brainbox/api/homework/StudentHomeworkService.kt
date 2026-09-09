package com.afrithecus.brainbox.api.homework

import com.afrithecus.brainbox.api.classes.repository.ClassMembershipRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.model.SubmissionType
import com.afrithecus.brainbox.api.exams.AutoGrader
import com.afrithecus.brainbox.api.homework.entity.HomeworkQuestionEntity
import com.afrithecus.brainbox.api.homework.repository.HomeworkQuestionRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.homework.web.HomeworkPayload
import com.afrithecus.brainbox.api.homework.web.StudentSubmitRequest
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.exams.QuestionCodec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * Student homework surface: my assignments (class roster + targeted ids) and
 * submissions (FREE_TEXT / CHECKLIST / OFFLINE hand-in).
 */
@Service
class StudentHomeworkService(
    private val homeworkRepository: HomeworkRepository,
    private val submissionRepository: HomeworkSubmissionRepository,
    private val membershipRepository: ClassMembershipRepository,
    private val teacherService: TeacherHomeworkService,
    private val questionRepository: HomeworkQuestionRepository,
    private val autoGrader: AutoGrader,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun myHomework(student: UserEntity): List<HomeworkPayload> {
        requireStudent(student)
        val myClassIds = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
        if (myClassIds.isEmpty()) return emptyList()
        val visible = homeworkRepository.findAllByIsDraftFalseAndIsActiveTrue()
            .filter { hw ->
                hw.classId in myClassIds && isAssigned(hw, student.id)
            }
            .sortedBy { it.dueDate }
        return visible.map { hw -> withSubmission(hw, student.id) }
    }

    @Transactional(readOnly = true)
    fun detail(student: UserEntity, homeworkId: String): HomeworkPayload {
        requireStudent(student)
        val hw = findVisible(student, homeworkId)
        return withSubmission(hw, student.id)
    }

    @Transactional
    fun submit(student: UserEntity, homeworkId: String, request: StudentSubmitRequest): HomeworkPayload {
        requireStudent(student)
        val hw = findVisible(student, homeworkId)
        validateContent(hw, request)
        val existing = submissionRepository.findByHomeworkIdAndStudentId(hw.id, student.id)
        if (existing != null && isLocked(hw, existing)) {
            throw conflict("Homework already graded; ask your teacher to return it before resubmitting")
        }
        val submission = (existing ?: HomeworkSubmissionEntity().apply {
            this.homeworkId = hw.id
            this.studentId = student.id
        }).apply {
            submissionText = request.submissionText?.takeIf { it.isNotBlank() }
            attachmentUrl = request.attachmentUrl?.takeIf { it.isNotBlank() }
            checklistAnswers = request.checklistAnswers?.let { mapper.writeValueAsString(it) }
            answersJson = request.answers?.let { mapper.writeValueAsString(it) }
            status = SubmissionStatus.PENDING
            grade = null
            submittedAt = clock.instant()
            updatedAt = clock.instant()
        }
        autoGradeIfQuestionSet(hw, submission, request)
        submissionRepository.save(submission)
        return withSubmission(hw, student.id)
    }

    // ------------------------------------------------------------ internals

    private fun requireStudent(user: UserEntity) {
        if (user.role != Role.STUDENT) throw ApiException(ApiErrorCode.FORBIDDEN, "Student access only")
    }

    private fun findVisible(student: UserEntity, homeworkId: String): HomeworkEntity {
        val hw = homeworkRepository.findById(homeworkId).orElse(null)
            ?: throw notFound("Homework not found")
        val myClassIds = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
        val visible = !hw.isDraft && hw.isActive && hw.classId in myClassIds && isAssigned(hw, student.id)
        if (!visible) throw notFound("Homework not found")
        return hw
    }

    private fun isAssigned(hw: HomeworkEntity, studentId: UUID): Boolean {
        val targeted = codec.parseList(hw.assignedStudentIds)
        return targeted.isNullOrEmpty() || targeted.contains(studentId.toString())
    }

    private fun validateContent(hw: HomeworkEntity, request: StudentSubmitRequest) {
        when (hw.submissionType) {
            SubmissionType.FREE_TEXT ->
                if (request.submissionText.isNullOrBlank() && request.attachmentUrl.isNullOrBlank()) {
                    throw invalidArgument("A FREE_TEXT submission needs text or an attachment")
                }
            SubmissionType.CHECKLIST -> {
                val items = codec.parseList(hw.checklistItems) ?: emptyList()
                val answers = request.checklistAnswers ?: emptyList()
                if (answers.isEmpty() || answers.any { it < 0 || it >= items.size }) {
                    throw invalidArgument("checklist answers must reference 1-" + items.size + " items")
                }
            }
            SubmissionType.OFFLINE_PHYSICAL_HANDIN -> {
                // physical hand-ins: submittedAt is the canonical signal; no text expected
                request.submissionText?.let { if (it.isNotBlank()) throw invalidArgument("Physical hand-ins do not take text") }
            }
            SubmissionType.EXAM_QUESTION_SET -> {
                if (request.answers == null || !request.answers.isObject) {
                    throw invalidArgument("Question-set homework needs an answers object")
                }
            }
            else -> { /* PAST_PAPER_REVIEW content arrives with past-paper flows */ }
        }
    }

    private fun withSubmission(hw: HomeworkEntity, studentId: UUID): HomeworkPayload {
        // Student payloads never receive question keys.
        val payload = teacherService.toPayload(hw, includeKeys = false)
        val submission = submissionRepository.findByHomeworkIdAndStudentId(hw.id, studentId)
        if (submission == null) return payload
        val (status, grade) = revealedState(hw, submission)
        return payload.copy(
            submissionStatus = status,
            grade = grade,
        )
    }

    /** AUTO_POST_COMPLETION hides the grade until the due date passes. */
    private fun revealedState(hw: HomeworkEntity, submission: HomeworkSubmissionEntity): Pair<String, Int?> {
        val autoPost = hw.gradingMode == com.afrithecus.brainbox.api.homework.model.GradingMode.AUTO_POST_COMPLETION
        val immediate = hw.gradingMode == com.afrithecus.brainbox.api.homework.model.GradingMode.AUTO_IMMEDIATE
        return if (submission.grade != null && (immediate || (autoPost && !hw.dueDate.isAfter(clock.instant())))) {
            Pair(SubmissionStatus.GRADED.name, submission.grade)
        } else if (submission.status == SubmissionStatus.GRADED) {
            Pair(SubmissionStatus.GRADED.name, submission.grade)
        } else {
            Pair(submission.status.name, null)
        }
    }

    private fun isLocked(hw: HomeworkEntity, submission: HomeworkSubmissionEntity): Boolean {
        if (submission.status == SubmissionStatus.GRADED) return true
        val autoPost = hw.gradingMode == com.afrithecus.brainbox.api.homework.model.GradingMode.AUTO_POST_COMPLETION
        return autoPost && submission.grade != null && !hw.dueDate.isAfter(clock.instant())
    }

    private fun autoGradeIfQuestionSet(hw: HomeworkEntity, submission: HomeworkSubmissionEntity, request: StudentSubmitRequest) {
        if (hw.submissionType != SubmissionType.EXAM_QUESTION_SET) return
        val questions = questionRepository.findAllByHomeworkIdOrderByOrderIndexAsc(hw.id)
        if (questions.isEmpty()) throw conflict("Homework has no questions to grade")
        val answers = request.answers ?: return
        var score = 0
        val total = questions.sumOf { it.points }
        questions.forEach { q ->
            val outcome = autoGrader.grade(q.qType, q.correctAnswer, null, q.points, answers.get(q.id.toString()))
            score += outcome.pointsEarned
        }
        val percentage = if (total > 0) (score * 100.0 / total).toInt() else 0
        when (hw.gradingMode) {
            com.afrithecus.brainbox.api.homework.model.GradingMode.AUTO_IMMEDIATE -> {
                submission.status = SubmissionStatus.GRADED
                submission.grade = percentage
                submission.gradedAt = clock.instant()
            }
            com.afrithecus.brainbox.api.homework.model.GradingMode.AUTO_POST_COMPLETION -> {
                // keep PENDING; grade reveals when the due date passes
                submission.status = SubmissionStatus.PENDING
                submission.grade = percentage
            }
            else -> { /* MANUAL: teacher grades */ }
        }
    }
}
