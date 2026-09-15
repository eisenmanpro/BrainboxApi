package com.afrithecus.brainbox.api.homework

import com.afrithecus.brainbox.api.classes.entity.TeacherClassEntity
import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.homework.entity.HomeworkEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkQuestionEntity
import com.afrithecus.brainbox.api.homework.entity.HomeworkSubmissionEntity
import com.afrithecus.brainbox.api.homework.model.GradingMode
import com.afrithecus.brainbox.api.homework.model.HomeworkScope
import com.afrithecus.brainbox.api.homework.model.SubmissionStatus
import com.afrithecus.brainbox.api.homework.model.SubmissionType
import com.afrithecus.brainbox.api.homework.repository.HomeworkQuestionRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkRepository
import com.afrithecus.brainbox.api.homework.repository.HomeworkSubmissionRepository
import com.afrithecus.brainbox.api.homework.web.BulkGradeItem
import com.afrithecus.brainbox.api.homework.web.GradeSubmissionRequest
import com.afrithecus.brainbox.api.homework.web.HomeworkPayload
import com.afrithecus.brainbox.api.homework.web.HomeworkProgressItem
import com.afrithecus.brainbox.api.homework.web.HomeworkQuestionRequest
import com.afrithecus.brainbox.api.homework.web.HomeworkUpsertRequest
import com.afrithecus.brainbox.api.homework.web.ReturnSubmissionRequest
import com.afrithecus.brainbox.api.homework.web.SubmissionPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.traditional.model.ExamTerm
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.web.QuestionPayload
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Teacher-side homework (web homework contract). Creates are idempotent upserts
 * keyed on the client-generated id; the teacher must own the target class.
 */
@Service
class TeacherHomeworkService(
    private val homeworkRepository: HomeworkRepository,
    private val submissionRepository: HomeworkSubmissionRepository,
    private val questionRepository: HomeworkQuestionRepository,
    private val classRepository: TeacherClassRepository,
    private val userRepository: UserRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional
    fun upsert(teacher: UserEntity, request: HomeworkUpsertRequest): HomeworkPayload {
        requireTeacher(teacher)
        val clazz = ownClass(teacher, request.classId)
        val type = parseSubmissionType(request.submissionType)
        val mode = request.gradingMode?.let { parseMode(it) }
        if (mode != null && mode != GradingMode.MANUAL && type != SubmissionType.EXAM_QUESTION_SET) {
            throw invalidArgument("AUTO grading is only available for EXAM_QUESTION_SET homework (question-set support is a follow-on)")
        }

        val existing = homeworkRepository.findById(request.id).orElse(null)
        if (existing != null && existing.teacherId != teacher.id) {
            throw conflict("Homework id is already used by another teacher")
        }
        val entity = existing ?: HomeworkEntity().apply { id = request.id }
        apply(entity, request, clazz, teacher)
        if (type == SubmissionType.EXAM_QUESTION_SET && request.questions.isNullOrEmpty()) {
            throw invalidArgument("EXAM_QUESTION_SET homework needs at least one question")
        }
        val wasManualGradeGate = mode == null || mode == GradingMode.MANUAL
        if (mode != null && mode != GradingMode.MANUAL && type != SubmissionType.EXAM_QUESTION_SET) {
            throw invalidArgument("AUTO grading is only available for EXAM_QUESTION_SET homework")
        }

        if (entity.submissionType == SubmissionType.CHECKLIST) {
            val items = request.checklistItems?.filter { it.isNotBlank() } ?: emptyList()
            if (items.isEmpty() || items.size > 20) {
                throw invalidArgument("CHECKLIST homework needs 1-20 checklist items")
            }
            entity.checklistItems = codec.toJson(items)
        } else {
            entity.checklistItems = null
        }
        homeworkRepository.save(entity)
        persistQuestions(entity, request.questions)
        return toPayload(entity, includeKeys = true)
    }

    private fun persistQuestions(homework: HomeworkEntity, questions: List<HomeworkQuestionRequest>?) {
        questionRepository.deleteByHomeworkId(homework.id)
        if (questions.isNullOrEmpty()) return
        val entities = questions.mapIndexed { index, q ->
            val qType = runCatching { QuestionType.valueOf(q.type.trim().uppercase()) }.getOrNull()
                ?: throw invalidArgument("question type has an invalid value")
            HomeworkQuestionEntity().apply {
                homeworkId = homework.id
                text = q.text
                this.qType = qType
                options = codec.toJson(q.options)
                correctAnswer = q.correctAnswer
                explanation = q.explanation
                points = q.points
                orderIndex = index
            }
        }
        questionRepository.saveAll(entities)
    }


    @Transactional(readOnly = true)
    fun list(teacher: UserEntity): List<HomeworkPayload> =
        homeworkRepository.findAllByTeacherIdAndIsActiveTrueOrderByDueDateAsc(teacher.id).map(::toPayload)

    @Transactional(readOnly = true)
    fun get(teacher: UserEntity, homeworkId: String): HomeworkPayload =
        toPayload(ownHomework(teacher, homeworkId))

    @Transactional
    fun delete(teacher: UserEntity, homeworkId: String) {
        val entity = ownHomework(teacher, homeworkId)
        homeworkRepository.delete(entity)
    }

    @Transactional(readOnly = true)
    fun submissions(teacher: UserEntity, homeworkId: String): List<SubmissionPayload> {
        val homework = ownHomework(teacher, homeworkId)
        val subs = submissionRepository.findAllByHomeworkId(homework.id)
        val names = userRepository.findAllById(subs.map { it.studentId }).associateBy { it.id }
        return subs.map { sub -> toSubmissionPayload(sub, names[sub.studentId]?.name ?: "Student") }
    }

    @Transactional
    fun grade(teacher: UserEntity, submissionIdRaw: String, request: GradeSubmissionRequest): SubmissionPayload {
        val submission = ownSubmission(teacher, submissionIdRaw)
        val clientAt = request.clientTimestamp?.let(Instant::ofEpochMilli)
        // Last write wins: a stale offline grade must not clobber a newer server value.
        if (clientAt == null || submission.gradedAt?.isAfter(clientAt) != true) {
            applyGrade(submission, teacher, request.grade, request.feedback, request.cbcStrandTag)
            submissionRepository.save(submission)
        }
        return toSubmissionPayload(submission, studentName(submission.studentId))
    }

    /** Grades many submissions for one homework in a single transaction. */
    @Transactional
    fun gradeBulk(teacher: UserEntity, homeworkId: String, items: List<BulkGradeItem>): List<SubmissionPayload> {
        val homework = ownHomework(teacher, homeworkId)
        val resolved = items.map { item ->
            val submission = ownSubmission(teacher, item.submissionId)
            if (submission.homeworkId != homework.id) throw notFound("Submission does not belong to this homework")
            submission to item
        }
        val names = userRepository.findAllById(resolved.map { it.first.studentId }.distinct()).associateBy { it.id }
        return resolved.map { (submission, item) ->
            val clientAt = item.clientTimestamp?.let(Instant::ofEpochMilli)
            if (clientAt == null || submission.gradedAt?.isAfter(clientAt) != true) {
                applyGrade(submission, teacher, item.grade, item.feedback, null)
                submissionRepository.save(submission)
            }
            toSubmissionPayload(submission, names[submission.studentId]?.name ?: "Student")
        }
    }

    private fun applyGrade(
        submission: HomeworkSubmissionEntity,
        teacher: UserEntity,
        grade: Int,
        feedback: String?,
        cbcStrandTag: String?,
    ) {
        submission.status = SubmissionStatus.GRADED
        submission.grade = grade
        submission.feedback = feedback
        cbcStrandTag?.let { submission.cbcStrandTag = it }
        submission.gradedBy = teacher.id
        submission.gradedAt = clock.instant()
        submission.updatedAt = clock.instant()
    }

    @Transactional
    fun returnSubmission(teacher: UserEntity, submissionIdRaw: String, request: ReturnSubmissionRequest): SubmissionPayload {
        val submission = ownSubmission(teacher, submissionIdRaw)
        val clientAt = request.clientTimestamp?.let(Instant::ofEpochMilli)
        if (clientAt != null && submission.updatedAt.isAfter(clientAt)) {
            return toSubmissionPayload(submission, studentName(submission.studentId))
        }
        submission.status = SubmissionStatus.RETURNED
        submission.grade = null
        submission.feedback = request.feedback
        submission.gradedBy = null
        submission.gradedAt = null
        submission.updatedAt = clock.instant()
        submissionRepository.save(submission)
        return toSubmissionPayload(submission, studentName(submission.studentId))
    }

    /** Closes homework without deleting it or its submissions. */
    @Transactional
    fun archive(teacher: UserEntity, homeworkId: String) {
        val entity = ownHomework(teacher, homeworkId)
        entity.isActive = false
        entity.updatedAt = clock.instant()
        homeworkRepository.save(entity)
    }

    @Transactional(readOnly = true)
    fun progress(teacher: UserEntity, ids: List<String>): List<HomeworkProgressItem> =
        ids.distinct().mapNotNull { raw ->
            val homework = homeworkRepository.findById(raw).orElse(null)
            if (homework == null || homework.teacherId != teacher.id) return@mapNotNull null
            val subs = submissionRepository.findAllByHomeworkId(homework.id)
            val graded = subs.filter { it.status == SubmissionStatus.GRADED }
            HomeworkProgressItem(
                homeworkId = homework.id,
                total = subs.size,
                graded = graded.size,
                pending = subs.count { it.status != SubmissionStatus.GRADED },
                avg = if (graded.isEmpty()) null else graded.mapNotNull { it.grade }.let { g -> if (g.isEmpty()) null else g.average().toInt() },
            )
        }

    // ------------------------------------------------------------ internals

    private fun ownHomework(teacher: UserEntity, id: String): HomeworkEntity {
        val entity = homeworkRepository.findById(id).orElse(null) ?: throw notFound("Homework not found")
        if (entity.teacherId != teacher.id) throw ApiException(ApiErrorCode.FORBIDDEN, "Not your homework")
        return entity
    }

    private fun ownSubmission(teacher: UserEntity, raw: String): HomeworkSubmissionEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("submission id is not a valid identifier")
        val submission = submissionRepository.findById(id).orElse(null)
            ?: throw notFound("Submission not found")
        val homework = homeworkRepository.findById(submission.homeworkId).orElse(null)
            ?: throw notFound("Homework not found")
        if (homework.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your homework submission")
        }
        return submission
    }

    private fun ownClass(teacher: UserEntity, raw: String): TeacherClassEntity {
        val id = runCatching { UUID.fromString(raw) }.getOrNull()
            ?: throw invalidArgument("classId is not a valid identifier")
        val clazz = classRepository.findById(id).orElse(null) ?: throw notFound("Class not found")
        if (clazz.teacherUserId != teacher.id || !clazz.isActive) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
        }
        return clazz
    }

    private fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    private fun apply(
        entity: HomeworkEntity,
        request: HomeworkUpsertRequest,
        clazz: TeacherClassEntity,
        teacher: UserEntity,
    ) {
        entity.classId = clazz.id
        entity.teacherId = teacher.id
        entity.teacherName = teacher.name
        entity.schoolId = clazz.schoolId
        entity.title = request.title.trim()
        entity.description = request.description.trim()
        entity.subject = request.subject.trim()
        entity.gradeLevel = request.gradeLevel
        entity.term = request.term ?: entity.term ?: termFor(entity.createdAt)
        entity.dueDate = Instant.ofEpochMilli(request.dueDate)
        val submissionType = parseSubmissionType(request.submissionType)
        if (submissionType == SubmissionType.PRACTICE_PAPER_REVIEW && request.relatedPaperCode.isNullOrBlank()) {
            throw invalidArgument("relatedPaperCode is required for PRACTICE_PAPER_REVIEW homework")
        }
        entity.submissionType = submissionType
        entity.gradingMode = request.gradingMode?.let { parseMode(it) }
        entity.isPracticePaperUnlocked = request.isPracticePaperUnlocked
        entity.relatedPaperCode = request.relatedPaperCode?.trim()?.takeIf { it.isNotEmpty() }
        entity.relatedDocumentId = request.relatedDocumentId?.trim()?.takeIf { it.isNotEmpty() }
        entity.cbcStrandTag = request.cbcStrandTag?.trim()?.takeIf { it.isNotEmpty() }
        entity.cbcSubStrandTag = request.cbcSubStrandTag?.trim()?.takeIf { it.isNotEmpty() }
        entity.assignedStudentIds = codec.toJson(request.assignedStudentIds?.filter { it.isNotBlank() })
        entity.scope = parseScope(request.scope)
        entity.isDraft = request.isDraft
        entity.isActive = request.isActive
        entity.updatedAt = clock.instant()
    }

    /** Month heuristic fallback (Jan-Apr = 1, May-Aug = 2, Sep-Dec = 3). */
    private fun termFor(instant: Instant): ExamTerm = when (LocalDate.ofInstant(instant, clock.zone).monthValue) {
        1, 2, 3, 4 -> ExamTerm.TERM_1
        5, 6, 7, 8 -> ExamTerm.TERM_2
        else -> ExamTerm.TERM_3
    }

    private fun parseSubmissionType(raw: String): SubmissionType =
        runCatching { SubmissionType.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("submissionType has an invalid value")

    private fun parseMode(raw: String): GradingMode =
        runCatching { GradingMode.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("gradingMode has an invalid value")

    private fun parseScope(raw: String): HomeworkScope =
        runCatching { HomeworkScope.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("scope has an invalid value")

    private fun studentName(studentId: UUID): String =
        userRepository.findById(studentId).map { it.name }.orElse("Student")

    internal fun toPayload(homework: HomeworkEntity, includeKeys: Boolean = true): HomeworkPayload = HomeworkPayload(
        id = homework.id,
        classId = homework.classId.toString(),
        teacherId = homework.teacherId.toString(),
        teacherName = homework.teacherName,
        schoolId = homework.schoolId?.toString(),
        title = homework.title,
        description = homework.description,
        subject = homework.subject,
        gradeLevel = homework.gradeLevel,
        term = homework.term,
        dueDate = homework.dueDate.toEpochMilli(),
        submissionType = homework.submissionType.name,
        checklistItems = codec.parseList(homework.checklistItems),
        gradingMode = homework.gradingMode?.name,
        isPracticePaperUnlocked = homework.isPracticePaperUnlocked,
        relatedPaperCode = homework.relatedPaperCode,
        relatedDocumentId = homework.relatedDocumentId,
        cbcStrandTag = homework.cbcStrandTag,
        cbcSubStrandTag = homework.cbcSubStrandTag,
        assignedStudentIds = codec.parseList(homework.assignedStudentIds),
        scope = homework.scope.name,
        isDraft = homework.isDraft,
        isActive = homework.isActive,
        createdAt = homework.createdAt.toEpochMilli(),
        questions = questionRepository.findAllByHomeworkIdOrderByOrderIndexAsc(homework.id)
            .map { q ->
                QuestionPayload(
                    id = q.id.toString(),
                    text = q.text,
                    type = q.qType.name,
                    options = codec.parseList(q.options),
                    correctAnswer = if (includeKeys) q.correctAnswer else null,
                    explanation = if (includeKeys) q.explanation else null,
                    points = q.points,
                    difficulty = 3,
                    topic = null,
                    subtopic = null,
                )
            },
    )

    private fun toSubmissionPayload(sub: HomeworkSubmissionEntity, studentName: String) = SubmissionPayload(
        id = sub.id.toString(),
        homeworkId = sub.homeworkId,
        studentId = sub.studentId.toString(),
        studentName = studentName,
        submissionText = sub.submissionText,
        attachmentUrl = sub.attachmentUrl,
        // Client vocabulary: a filed, not-yet-graded submission is SUBMITTED.
        status = if (sub.status == SubmissionStatus.PENDING) "SUBMITTED" else sub.status.name,
        submittedAt = sub.submittedAt.toEpochMilli(),
        grade = sub.grade,
        feedback = sub.feedback,
        cbcStrandTag = sub.cbcStrandTag,
        gradedAt = sub.gradedAt?.toEpochMilli(),
        isGraded = sub.status == SubmissionStatus.GRADED,
    )
}
