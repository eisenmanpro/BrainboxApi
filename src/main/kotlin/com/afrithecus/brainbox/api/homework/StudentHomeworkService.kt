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
import com.afrithecus.brainbox.api.common.domain.GradeNormalizer
import com.afrithecus.brainbox.api.homework.web.StudentHomeworkPayload
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
    fun myHomework(student: UserEntity, schoolId: String? = null, grade: String? = null, classId: String? = null): List<StudentHomeworkPayload> {
        requireStudent(student)
        val myClassIds = membershipRepository.findAllByStudentId(student.id).map { it.classId }.toSet()
        if (myClassIds.isEmpty()) return emptyList()
        val gradeKey = GradeNormalizer.canonicalKey(grade)
        val visible = homeworkRepository.findAllByIsDraftFalseAndIsActiveTrue()
            .filter { hw ->
                hw.classId in myClassIds && isAssigned(hw, student.id) &&
                    (classId.isNullOrBlank() || hw.classId.toString() == classId) &&
                    (schoolId.isNullOrBlank() || hw.schoolId == null || hw.schoolId.toString() == schoolId) &&
                    (gradeKey == null || hw.gradeLevel == gradeKey)
            }
            .sortedBy { it.dueDate }
        return visible.map { hw -> studentPayload(hw, student.id) }
    }

    @Transactional(readOnly = true)
    fun detail(student: UserEntity, homeworkId: String): StudentHomeworkPayload {
        requireStudent(student)
        val hw = findVisible(student, homeworkId)
        return studentPayload(hw, student.id)
    }

    @Transactional
    fun submit(student: UserEntity, homeworkId: String, request: StudentSubmitRequest): StudentHomeworkPayload {
        requireStudent(student)
        val hw = findVisible(student, homeworkId)
        validateContent(hw, request)
        val existing = submissionRepository.findByHomeworkIdAndStudentId(hw.id, student.id)
        val clientId = request.clientSubmissionId?.takeIf { it.isNotBlank() }
        // Offline replay of the same attempt is a no-op.
        if (existing != null && clientId != null && existing.clientSubmissionId == clientId) {
            return studentPayload(hw, student.id)
        }
        if (existing != null && isLocked(hw, existing)) {
            throw conflict("Homework already graded; ask your teacher to return it before resubmitting")
        }
        val submission = (existing ?: HomeworkSubmissionEntity().apply {
            this.homeworkId = hw.id
            this.studentId = student.id
        }).apply {
            submissionText = request.submissionText?.takeIf { it.isNotBlank() }
            attachmentUrl = request.attachmentUrl?.takeIf { it.isNotBlank() }
            checklistAnswers = checklistIndices(request)?.let { mapper.writeValueAsString(it) }
            answersJson = request.answers?.let { mapper.writeValueAsString(it) }
            clientSubmissionId = clientId ?: existing?.clientSubmissionId
            status = SubmissionStatus.PENDING
            grade = null
            submittedAt = clock.instant()
            updatedAt = clock.instant()
        }
        autoGradeIfQuestionSet(hw, submission, request)
        submissionRepository.save(submission)
        return studentPayload(hw, student.id)
    }

    private fun checklistIndices(request: StudentSubmitRequest): List<Int>? =
        request.checklistAnswers
            ?: request.answerNotes?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }

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
                val answers = checklistIndices(request) ?: emptyList()
                if (answers.isEmpty() || answers.any { it < 0 || it >= items.size }) {
                    throw invalidArgument("checklist answers must reference 1-" + items.size + " items")
                }
            }
            SubmissionType.OFFLINE_PHYSICAL_HANDIN -> {
                // physical hand-ins: submittedAt is the canonical signal; no text expected
                request.submissionText?.let { if (it.isNotBlank()) throw invalidArgument("Physical hand-ins do not take text") }
            }
            SubmissionType.EXAM_QUESTION_SET -> {
                // The client may submit the linked exam's outcome instead of raw
                // answers; auto-grading still runs when answers are supplied.
                request.answers?.let { if (!it.isObject) throw invalidArgument("Question-set answers must be an object") }
            }
            else -> { /* PAST_PAPER_REVIEW content arrives with past-paper flows */ }
        }
    }

    private fun studentPayload(hw: HomeworkEntity, studentId: UUID): StudentHomeworkPayload {
        val submission = submissionRepository.findByHomeworkIdAndStudentId(hw.id, studentId)
        val visible = submission != null && gradeVisible(hw, submission)
        return StudentHomeworkPayload(
            id = hw.id,
            title = hw.title,
            description = hw.description,
            subject = hw.subject,
            dueDate = hw.dueDate.toEpochMilli(),
            teacherId = hw.teacherId.toString(),
            teacherName = hw.teacherName,
            studentId = if (submission != null) studentId.toString() else null,
            status = studentStatus(hw, submission),
            grade = if (visible) submission?.grade else null,
            feedback = if (visible) submission?.feedback else null,
            type = hw.submissionType.name,
            relatedDocumentId = hw.relatedDocumentId,
            relatedPaperCode = hw.relatedPaperCode,
            questionSetId = if (hw.submissionType == SubmissionType.EXAM_QUESTION_SET) hw.relatedDocumentId else null,
            submissionText = submission?.submissionText,
            taskSteps = codec.parseList(hw.checklistItems),
            answerNotes = submission?.checklistAnswers?.let { joinIndices(it) },
            attachmentUrl = submission?.attachmentUrl,
            schoolId = hw.schoolId?.toString().orEmpty(),
            classId = hw.classId.toString(),
            scope = hw.scope.name,
            gradeLevel = hw.gradeLevel.takeIf { it > 0 }?.toString(),
            assignedStudentIds = codec.parseList(hw.assignedStudentIds) ?: emptyList(),
            gradingMode = hw.gradingMode?.name,
            cbcStrandTag = hw.cbcStrandTag,
            clientSubmissionId = submission?.clientSubmissionId,
        )
    }

    /** PENDING (no submission) | SUBMITTED | GRADED | RETURNED. */
    private fun studentStatus(hw: HomeworkEntity, submission: HomeworkSubmissionEntity?): String = when {
        submission == null -> "PENDING"
        submission.status == SubmissionStatus.RETURNED -> "RETURNED"
        gradeVisible(hw, submission) -> SubmissionStatus.GRADED.name
        else -> "SUBMITTED"
    }

    /** AUTO_POST_COMPLETION hides the grade until the due date passes. */
    private fun gradeVisible(hw: HomeworkEntity, submission: HomeworkSubmissionEntity): Boolean {
        if (submission.grade == null) return false
        val autoPost = hw.gradingMode == com.afrithecus.brainbox.api.homework.model.GradingMode.AUTO_POST_COMPLETION
        return !autoPost || !hw.dueDate.isAfter(clock.instant())
    }

    private fun joinIndices(json: String): String? =
        runCatching { mapper.readTree(json) }.getOrNull()
            ?.takeIf { it.isArray }
            ?.let { node -> (0 until node.size()).joinToString(",") { node.get(it).asString() } }

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
