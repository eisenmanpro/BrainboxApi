package com.afrithecus.brainbox.api.exams

import com.afrithecus.brainbox.api.classes.repository.TeacherClassRepository
import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.common.error.conflict
import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.entity.ExamSectionEntity
import com.afrithecus.brainbox.api.exams.entity.TeacherQuestionBankEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.repository.ExamSectionRepository
import com.afrithecus.brainbox.api.exams.repository.TeacherQuestionBankRepository
import com.afrithecus.brainbox.api.exams.web.TeacherExamPayload
import com.afrithecus.brainbox.api.exams.web.TeacherExamSectionPayload
import com.afrithecus.brainbox.api.exams.web.TeacherQuestionPayload
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Teacher digital exam authoring (docs/ongoing/api_exams_changes.md): client-id
 * idempotent CRUD, repeat-safe delete, publish and the reusable question bank.
 * Everything is self-scoped to the calling teacher; exams are stored in the
 * shared exams tables so the student session engine can serve them once
 * published.
 */
@Service
class TeacherExamService(
    private val examRepository: ExamRepository,
    private val questionRepository: ExamQuestionRepository,
    private val sectionRepository: ExamSectionRepository,
    private val bankRepository: TeacherQuestionBankRepository,
    private val classRepository: TeacherClassRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun list(teacher: UserEntity): List<TeacherExamPayload> {
        requireTeacher(teacher)
        return examRepository.findAllByCreatedByOrderByCreatedAtDesc(teacher.id).map(::toPayload)
    }

    /** Idempotent upsert keyed on the client-supplied exam id. */
    @Transactional
    fun create(teacher: UserEntity, request: TeacherExamPayload): TeacherExamPayload {
        requireTeacher(teacher)
        val clientId = request.id.trim().takeIf { it.isNotEmpty() } ?: "exam_" + UUID.randomUUID()
        val existing = examRepository.findByClientId(clientId)
        if (existing != null && existing.createdBy != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your exam")
        }
        val exam = existing ?: ExamEntity().apply {
            this.clientId = clientId
            createdBy = teacher.id
            examType = ExamType.DIGITAL
        }
        applyExam(exam, request, teacher)
        examRepository.saveAndFlush(exam)
        replaceSections(exam, request)
        replaceQuestions(exam, request)
        refreshTotals(exam)
        return toPayload(exam)
    }

    @Transactional
    fun update(teacher: UserEntity, examId: String, request: TeacherExamPayload): TeacherExamPayload {
        requireTeacher(teacher)
        val exam = requireOwnedExam(teacher, examId)
        applyExam(exam, request, teacher)
        examRepository.saveAndFlush(exam)
        replaceSections(exam, request)
        replaceQuestions(exam, request)
        refreshTotals(exam)
        return toPayload(exam)
    }

    /** Repeat-safe: an unknown exam is treated as already deleted. */
    @Transactional
    fun delete(teacher: UserEntity, examId: String) {
        requireTeacher(teacher)
        val exam = ownExam(teacher, examId) ?: return
        examRepository.delete(exam)
        examRepository.flush()
    }

    @Transactional
    fun publish(teacher: UserEntity, examId: String): TeacherExamPayload {
        requireTeacher(teacher)
        val exam = requireOwnedExam(teacher, examId)
        if (questionRepository.countByExamId(exam.id) == 0L) {
            throw conflict("Cannot publish an exam without questions")
        }
        exam.status = ExamStatus.PUBLISHED
        examRepository.saveAndFlush(exam)
        return toPayload(exam)
    }

    @Transactional(readOnly = true)
    fun questionBank(teacher: UserEntity): List<TeacherQuestionPayload> {
        requireTeacher(teacher)
        return bankRepository.findAllByTeacherIdOrderByCreatedAtDesc(teacher.id).map { bank ->
            TeacherQuestionPayload(
                id = bank.clientId,
                examId = "",
                questionText = bank.text,
                questionType = bank.qType.name,
                points = bank.points,
                difficulty = bank.difficulty,
                options = codec.parseList(bank.options),
                correctAnswer = bank.correctAnswer,
                cbcStrandTag = bank.cbcStrandTag,
                isFromBank = true,
            )
        }
    }

    /** Idempotent per client-supplied question id. */
    @Transactional
    fun addToQuestionBank(teacher: UserEntity, request: TeacherQuestionPayload): TeacherQuestionPayload {
        requireTeacher(teacher)
        val key = request.id.trim().takeIf { it.isNotEmpty() } ?: "qb_" + UUID.randomUUID()
        val existing = bankRepository.findByClientId(key)
        if (existing != null && existing.teacherId != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your question")
        }
        val entity = existing ?: TeacherQuestionBankEntity().apply {
            clientId = key
            teacherId = teacher.id
        }
        val text = request.questionText.trim()
        if (text.isEmpty()) throw invalidArgument("Question text is required")
        entity.text = text
        entity.qType = parseQuestionType(request.questionType)
        entity.options = codec.toJson(request.options)
        entity.correctAnswer = request.correctAnswer?.takeIf { it.isNotBlank() }
        entity.points = request.points.coerceAtLeast(0)
        entity.difficulty = request.difficulty.coerceIn(1, 5)
        entity.matchingPairs =
            if (entity.qType == QuestionType.MATCHING) matchingPairsJson(request.correctAnswer) else null
        entity.cbcStrandTag = request.cbcStrandTag?.trim()?.takeIf { it.isNotEmpty() }
        entity.schoolId = teacher.schoolId
        bankRepository.saveAndFlush(entity)
        return TeacherQuestionPayload(
            id = key,
            examId = "",
            questionText = entity.text,
            questionType = entity.qType.name,
            points = entity.points,
            difficulty = entity.difficulty,
            options = codec.parseList(entity.options),
            correctAnswer = entity.correctAnswer,
            cbcStrandTag = entity.cbcStrandTag,
            isFromBank = true,
        )
    }

    // ------------------------------------------------------------ internals

    internal fun requireTeacher(user: UserEntity) {
        if (user.role != Role.TEACHER) throw ApiException(ApiErrorCode.FORBIDDEN, "Teacher access only")
    }

    /**
     * Resolves an exam the teacher owns by client id first (the app's stable id)
     * then by server UUID; a foreign exam is a forbidden, not a not-found, so a
     * teacher cannot probe another teacher's data.
     */
    internal fun ownExam(teacher: UserEntity, raw: String): ExamEntity? {
        val byClient = examRepository.findByClientId(raw)
        if (byClient != null) {
            if (byClient.createdBy != teacher.id) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Not your exam")
            }
            return byClient
        }
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        val exam = examRepository.findById(id).orElse(null) ?: return null
        if (exam.createdBy != teacher.id) {
            throw ApiException(ApiErrorCode.FORBIDDEN, "Not your exam")
        }
        return exam
    }

    internal fun requireOwnedExam(teacher: UserEntity, raw: String): ExamEntity =
        ownExam(teacher, raw) ?: throw notFound("Exam not found")

    internal fun questionsOf(examId: UUID): List<ExamQuestionEntity> =
        questionRepository.findAllByExamIdOrderByOrderIndexAsc(examId)

    internal fun toPayload(exam: ExamEntity): TeacherExamPayload {
        val questions = questionsOf(exam.id)
        val sections = sectionRepository.findAllByExamIdOrderBySortOrderAsc(exam.id)
        return TeacherExamPayload(
            id = exam.clientId ?: exam.id.toString(),
            classId = exam.classId?.toString() ?: "",
            teacherId = exam.createdBy.toString(),
            schoolId = exam.schoolId?.toString() ?: "",
            title = exam.title,
            subject = exam.subject,
            gradeLevel = exam.gradeLevel ?: 0,
            durationMinutes = exam.durationMinutes,
            totalPoints = if (exam.totalPoints > 0) exam.totalPoints else questions.sumOf { it.points },
            difficulty = exam.difficulty,
            questions = questions.map(::toQuestionPayload),
            sections = sections.map(::toSectionPayload),
            openDate = exam.openAt?.toEpochMilli(),
            closeDate = exam.closeAt?.toEpochMilli(),
            isPublished = exam.status == ExamStatus.PUBLISHED,
            createdAt = exam.createdAt.toEpochMilli(),
            scope = if (exam.classId != null) "SCHOOL_GRADE_CLASS" else "SCHOOL_GRADE",
            term = exam.term ?: defaultTerm(),
        )
    }

    internal fun toQuestionPayload(question: ExamQuestionEntity): TeacherQuestionPayload =
        TeacherQuestionPayload(
            id = question.clientId ?: question.id.toString(),
            examId = question.examId.toString(),
            sectionId = question.sectionId,
            questionText = question.text,
            questionType = question.qType.name,
            points = question.points,
            difficulty = question.difficulty,
            options = codec.parseList(question.options),
            correctAnswer = question.correctAnswer,
            cbcStrandTag = question.cbcStrandTag,
            sortOrder = question.orderIndex,
            isKeyQuestion = question.isKeyQuestion,
            requiresExplanation = question.requiresExplanation,
            isFromBank = question.isFromBank,
        )

    private fun toSectionPayload(section: ExamSectionEntity): TeacherExamSectionPayload =
        TeacherExamSectionPayload(
            id = section.clientId,
            examId = section.examId.toString(),
            title = section.title,
            instructions = section.instructions,
            durationMinutes = section.durationMinutes,
            sortOrder = section.sortOrder,
        )

    private fun applyExam(exam: ExamEntity, request: TeacherExamPayload, teacher: UserEntity) {
        val title = request.title.trim()
        if (title.isEmpty()) throw invalidArgument("Exam title is required")
        val subject = request.subject.trim()
        if (subject.isEmpty()) throw invalidArgument("Exam subject is required")
        if (request.isPublished && request.questions.isEmpty()) {
            throw conflict("Cannot publish an exam without questions")
        }
        val classId = request.classId.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            val id = runCatching { UUID.fromString(raw) }.getOrNull()
                ?: throw invalidArgument("classId is not a valid identifier")
            val clazz = classRepository.findById(id).orElse(null)
                ?: throw invalidArgument("Class not found")
            if (clazz.teacherUserId != teacher.id) {
                throw ApiException(ApiErrorCode.FORBIDDEN, "Not your class")
            }
            id
        }
        exam.title = title
        exam.subject = subject
        exam.classId = classId
        exam.gradeLevel = request.gradeLevel.takeIf { it > 0 }
        exam.durationMinutes = request.durationMinutes.coerceAtLeast(0)
        exam.difficulty = request.difficulty.coerceIn(1, 5)
        exam.schoolId = teacher.schoolId
        exam.scope = when {
            classId != null -> ExamScope.SCHOOL_GRADE_CLASS
            teacher.schoolId != null -> ExamScope.SCHOOL
            else -> ExamScope.GLOBAL
        }
        exam.status = if (request.isPublished) ExamStatus.PUBLISHED else ExamStatus.DRAFT
        exam.term = normalizeTerm(request.term)
        exam.openAt = request.openDate?.let(Instant::ofEpochMilli)
        exam.closeAt = request.closeDate?.let(Instant::ofEpochMilli)
    }

    private fun replaceSections(exam: ExamEntity, request: TeacherExamPayload) {
        val kept = mutableSetOf<String>()
        request.sections.forEachIndexed { index, section ->
            val key = section.id.trim().takeIf { it.isNotEmpty() } ?: "sec_" + UUID.randomUUID()
            kept += key
            val entity = sectionRepository.findByExamIdAndClientId(exam.id, key)
                ?: ExamSectionEntity().apply {
                    examId = exam.id
                    clientId = key
                }
            entity.title = section.title.trim().ifEmpty { "Section " + (index + 1) }
            entity.instructions = section.instructions?.trim()?.takeIf { it.isNotEmpty() }
            entity.durationMinutes = section.durationMinutes
            entity.sortOrder = index
            sectionRepository.save(entity)
        }
        sectionRepository.findAllByExamIdOrderBySortOrderAsc(exam.id)
            .filter { it.clientId !in kept }
            .forEach { sectionRepository.delete(it) }
    }

    /**
     * Upserts each question on its client id so the server UUID stays stable
     * across re-saves; review marks and stored grading details hang off that
     * UUID, so recreating questions would discard them. Questions dropped from
     * the request are deleted (their marks cascade away).
     */
    private fun replaceQuestions(exam: ExamEntity, request: TeacherExamPayload) {
        val kept = mutableSetOf<String>()
        request.questions.forEachIndexed { index, question ->
            val text = question.questionText.trim()
            if (text.isEmpty()) throw invalidArgument("Question text is required")
            val type = parseQuestionType(question.questionType)
            val key = question.id.trim().takeIf { it.isNotEmpty() } ?: "q_" + UUID.randomUUID()
            kept += key
            val entity = questionRepository.findByExamIdAndClientId(exam.id, key)
                ?: ExamQuestionEntity().apply {
                    examId = exam.id
                    clientId = key
                }
            entity.text = text
            entity.qType = type
            entity.options = codec.toJson(question.options)
            entity.correctAnswer = question.correctAnswer?.takeIf { it.isNotBlank() }
            entity.points = question.points.coerceAtLeast(0)
            entity.difficulty = question.difficulty.coerceIn(1, 5)
            entity.matchingPairs =
                if (type == QuestionType.MATCHING) matchingPairsJson(question.correctAnswer) else null
            entity.cbcStrandTag = question.cbcStrandTag?.trim()?.takeIf { it.isNotEmpty() }
            entity.sectionId = question.sectionId?.trim()?.takeIf { it.isNotEmpty() }
            entity.isKeyQuestion = question.isKeyQuestion
            entity.requiresExplanation = question.requiresExplanation
            entity.isFromBank = question.isFromBank
            entity.orderIndex = index
            questionRepository.save(entity)
        }
        questionRepository.findAllByExamIdOrderByOrderIndexAsc(exam.id)
            .filter { (it.clientId ?: "") !in kept }
            .forEach { questionRepository.delete(it) }
    }

    private fun refreshTotals(exam: ExamEntity) {
        val questions = questionsOf(exam.id)
        exam.questionCount = questions.size
        exam.totalPoints = questions.sumOf { it.points }
        examRepository.saveAndFlush(exam)
    }

    /**
     * The teacher app encodes MATCHING keys as a JSON list of {left,right} pairs
     * (its own model); the grader wants a flat {left:right} map, so derive one.
     */
    private fun matchingPairsJson(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null
        val pairs = LinkedHashMap<String, String>()
        when {
            node.isArray -> for (i in 0 until node.size()) {
                val item = node.get(i)
                val left = item.get("left")?.asString() ?: item.get("key")?.asString() ?: continue
                val right = item.get("right")?.asString() ?: item.get("value")?.asString() ?: continue
                pairs[left] = right
            }
            node.isObject -> for (entry in node.properties()) pairs[entry.key] = entry.value.asString()
        }
        return if (pairs.isEmpty()) null else codec.toJson(pairs)
    }

    private fun normalizeTerm(raw: String): String {
        val upper = raw.trim().uppercase()
        return if (upper in TERMS) upper else defaultTerm()
    }

    private fun defaultTerm(): String {
        val month = clock.instant().atZone(ZoneOffset.UTC).monthValue
        return when (month) {
            in 1..4 -> "TERM_1"
            in 5..8 -> "TERM_2"
            else -> "TERM_3"
        }
    }

    private fun parseQuestionType(raw: String): QuestionType =
        runCatching { QuestionType.valueOf(raw.trim().uppercase()) }.getOrNull()
            ?: throw invalidArgument("Unknown question type: " + raw)

    private companion object {
        val TERMS = setOf("TERM_1", "TERM_2", "TERM_3")
    }
}
