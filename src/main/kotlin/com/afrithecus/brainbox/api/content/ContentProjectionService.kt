package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.invalidArgument
import com.afrithecus.brainbox.api.common.error.notFound
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.validation.ContentValidationService
import com.afrithecus.brainbox.api.exams.QuestionCodec
import com.afrithecus.brainbox.api.exams.entity.ExamEntity
import com.afrithecus.brainbox.api.exams.entity.ExamQuestionEntity
import com.afrithecus.brainbox.api.exams.model.ExamScope
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.model.QuestionType
import com.afrithecus.brainbox.api.exams.repository.ExamQuestionRepository
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.CanonicalSubject
import com.afrithecus.brainbox.api.learning.entity.LearningContentEntity
import com.afrithecus.brainbox.api.learning.entity.LearningPostEntity
import com.afrithecus.brainbox.api.learning.entity.ReadableFileEntity
import com.afrithecus.brainbox.api.learning.model.ContentType
import com.afrithecus.brainbox.api.learning.model.FileType
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.LearningContentRepository
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.ReadableFileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.util.UUID

/** What a [ContentProjectionService.project] call wrote, for the caller to route on. */
data class ProjectionResult(
    /** POST for a book-like unit, FILE for a chunk, EXAM for a practice paper. */
    val kind: String,
    val postId: UUID? = null,
    val fileId: UUID? = null,
    /** Practice-paper exam id (same as the content unit id); null for other kinds. */
    val examId: UUID? = null,
)

/**
 * Phase 7.3: projects the internal content_units cache into the client-facing
 * tables. A book-like unit (NOTES/BOOK/QUIZ/FLASHCARDS/LESSON) becomes a
 * learning_posts row plus learning_content blocks; a CHUNK becomes a readable_files
 * row with an inline body. Projection is idempotent by unit id: re-projecting updates
 * the same rows and replaces the content blocks rather than duplicating them.
 *
 * Only REVIEWED units project as learner-visible; anything else projects hidden
 * (isPublished/isActive false) so the reviewed-only read filters keep it out.
 */
@Service
class ContentProjectionService(
    private val contentUnits: ContentUnitRepository,
    private val unitSteps: ContentUnitStepRepository,
    private val unitQuestions: ContentUnitQuestionRepository,
    private val concepts: ConceptRepository,
    private val curriculumMaps: CurriculumMapRepository,
    private val posts: LearningPostRepository,
    private val contents: LearningContentRepository,
    private val readables: ReadableFileRepository,
    private val exams: ExamRepository,
    private val examQuestions: ExamQuestionRepository,
    private val users: UserRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
    private val autoApproval: AutoApprovalService,
) {

    @Transactional
    fun project(unitId: UUID): ProjectionResult {
        val unit = contentUnits.findById(unitId).orElse(null)
            ?: throw notFound("Content unit not found")
        // Projection is the "make this ready for learners" point, so the confidence gate runs
        // here first: when the policy enables it and the unit validates cleanly, this flips the
        // unit to REVIEWED (recording auto_approved on the outcome) before the read filters see it.
        autoApproval.maybeAutoApprove(ContentValidationService.CONTENT_TYPE_UNIT, unitId)
        val concept = unit.conceptId?.let { concepts.findById(it).orElse(null) }
        val reviewed = unit.reviewState == REVIEWED
        return when (unit.taskType.trim().uppercase()) {
            "NOTES", "BOOK", "QUIZ", "FLASHCARDS", "LESSON", "STUDY_GUIDE" ->
                projectBook(unit, concept?.name, reviewed)
            "CHUNK" -> projectChunk(unit, concept?.name, reviewed)
            "PRACTICE_PAPER" -> projectPracticePaper(unit, concept?.name, reviewed)
            else -> throw invalidArgument("task type is not projectable: " + unit.taskType)
        }
    }

    // ------------------------------------------------------------ book path

    private fun projectBook(unit: ContentUnitEntity, conceptName: String?, reviewed: Boolean): ProjectionResult {
        val existing = posts.findById(unit.id).orElse(null)
        val post = existing ?: LearningPostEntity().apply { id = unit.id }
        post.title = unit.title ?: conceptName ?: unit.subject
        post.subject = CanonicalSubject.required(unit.subject)
        post.customSubjectName = CanonicalSubject.custom(unit.subject)
        post.topic = conceptName
        post.subtopic = null
        post.imageUrl = null
        post.scope = LearningScope.GLOBAL
        post.schoolId = null
        post.gradeLevel = unit.gradeLevel
        post.teacherId = null
        post.cbcStrand = strandName(unit.conceptId)
        post.cbcSubStrand = null
        post.status = if (reviewed) PUBLISHED else DRAFT
        post.isPublished = reviewed
        post.publishAt = null
        post.createdBy = systemAuthorId()
        posts.save(post)

        val steps = unitSteps.findAllByUnitIdOrderByOrderIndexAsc(unit.id)
        val questions = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unit.id)

        // Replace, don't append: the same unit id must never accumulate blocks.
        val previous = contents.findAllByPostIdOrderByOrderIndexAsc(unit.id)
        if (previous.isNotEmpty()) contents.deleteAll(previous)

        steps.forEach { step ->
            contents.save(
                LearningContentEntity().apply {
                    postId = post.id
                    contentType = ContentType.NOTES
                    title = step.title
                    content = step.body
                    durationMinutes = 0
                    orderIndex = step.orderIndex
                    thumbnailUrl = null
                    metadata = null
                }
            )
        }

        if (questions.isNotEmpty()) {
            contents.save(
                LearningContentEntity().apply {
                    postId = post.id
                    contentType = ContentType.QUIZ
                    content = null
                    orderIndex = (steps.maxOfOrNull { it.orderIndex } ?: -1) + 1
                    thumbnailUrl = null
                    metadata = quizMetadataJson(questions)
                }
            )
        }
        return ProjectionResult(kind = "POST", postId = unit.id)
    }

    // ----------------------------------------------------------- chunk path

    private fun projectChunk(unit: ContentUnitEntity, conceptName: String?, reviewed: Boolean): ProjectionResult {
        val existing = readables.findById(unit.id).orElse(null)
        val file = existing ?: ReadableFileEntity().apply { id = unit.id }
        file.title = unit.title ?: conceptName ?: unit.subject
        file.subject = CanonicalSubject.required(unit.subject)
        file.authorName = unit.authorName
        file.category = "Notes"
        file.docType = "PLAINTEXT"
        file.fileUrl = ""
        file.fileType = FileType.TXT
        file.pageCount = 0
        file.sizeBytes = (unit.body?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0).toLong()
        file.body = unit.body
        file.scope = LearningScope.GLOBAL
        file.gradeLevel = unit.gradeLevel
        file.isActive = reviewed
        file.createdBy = systemAuthorId()
        readables.save(file)
        return ProjectionResult(kind = "FILE", fileId = unit.id)
    }

    // ---------------------------------------------------- practice paper path

    /**
     * Phase 7.6a: a PRACTICE_PAPER unit becomes one exam row plus its questions, so
     * the existing `GET /practice-papers/{examId}/content` read serves it with the
     * marking scheme intact. The exam id is the content unit id, and re-projecting
     * deletes and rewrites the questions, so a replay never duplicates either.
     *
     * A generated paper is marked as ours twice: `created_by` is the stable system
     * author (never a real user) and `client_id` carries a `gen:` generation-key
     * code. The year is the unit's own generation year, never a real past paper
     * year, and the title never claims a national paper.
     */
    private fun projectPracticePaper(
        unit: ContentUnitEntity,
        conceptName: String?,
        reviewed: Boolean,
    ): ProjectionResult {
        val questions = unitQuestions.findAllByUnitIdOrderByOrderIndexAsc(unit.id)
        val exam = exams.findById(unit.id).orElse(null) ?: ExamEntity().apply { id = unit.id }
        exam.title = unit.title?.takeIf { it.isNotBlank() }
            ?: conceptName
            ?: (unit.gradeLevel + " " + unit.subject + " Practice Paper")
        exam.subject = unit.subject
        exam.examType = ExamType.PRACTICE_PAPER
        exam.scope = ExamScope.GLOBAL
        exam.schoolId = null
        exam.durationMinutes = practicePaperMinutes(questions.size)
        exam.questionCount = questions.size
        exam.difficulty = averageDifficulty(questions)
        exam.status = if (reviewed) ExamStatus.PUBLISHED else ExamStatus.DRAFT
        exam.examYear = generatedYear(unit)
        exam.isMcp = false
        exam.coverImageUrl = null
        exam.createdBy = systemAuthorId()
        exam.clientId = generatedClientId(unit.generationKey)
        exam.classId = null
        exam.gradeLevel = parseGrade(unit.gradeLevel)
        exam.totalPoints = questions.sumOf { it.points }
        exam.term = null
        exam.openAt = null
        exam.closeAt = null
        exams.save(exam)

        // Replace, don't append: the same unit id must never accumulate questions.
        val previous = examQuestions.findAllByExamIdOrderByOrderIndexAsc(unit.id)
        if (previous.isNotEmpty()) examQuestions.deleteAll(previous)

        questions.forEach { question ->
            examQuestions.save(
                ExamQuestionEntity().apply {
                    examId = unit.id
                    text = question.text
                    qType = examQuestionType(question.qType)
                    options = question.options
                    correctAnswer = question.correctAnswer
                    explanation = question.explanation
                    points = question.points
                    difficulty = question.difficulty.coerceIn(1, 5)
                    matchingPairs = question.matchingPairs
                    topic = conceptName
                    subtopic = null
                    orderIndex = question.orderIndex
                    clientId = null
                    sectionId = null
                    cbcStrandTag = null
                    isKeyQuestion = false
                    requiresExplanation = false
                    isFromBank = false
                }
            )
        }
        return ProjectionResult(kind = "EXAM", examId = unit.id)
    }

    // ------------------------------------------------------------ internals

    private fun strandName(conceptId: UUID?): String? {
        if (conceptId == null) return null
        return curriculumMaps
            .findFirstByConceptIdAndCountryCodeAndCurriculumOrderBySortOrderAsc(conceptId, "KE", "CBC")
            ?.strandName
    }

    /** A sensible paper duration in minutes: three minutes per question, at least 30. */
    private fun practicePaperMinutes(questionCount: Int): Int =
        maxOf(MIN_PAPER_MINUTES, questionCount * MINUTES_PER_QUESTION)

    /** The paper difficulty is the rounded mean of its question difficulties. */
    private fun averageDifficulty(questions: List<ContentUnitQuestionEntity>): Int {
        if (questions.isEmpty()) return DEFAULT_DIFFICULTY
        return (questions.sumOf { it.difficulty }.toDouble() / questions.size)
            .toInt()
            .coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    /** The generated year is the unit's own year, never a real national-paper year. */
    private fun generatedYear(unit: ContentUnitEntity): Int =
        unit.createdAt.atZone(ZoneOffset.UTC).year

    /** A `gen:` provenance code from the deterministic generation key, capped to the column. */
    private fun generatedClientId(generationKey: String): String =
        (GENERATED_CLIENT_PREFIX + generationKey).take(MAX_CLIENT_ID_CHARS)

    /** The numeric grade ("Grade 4" -> 4); null when the unit has no parseable grade. */
    private fun parseGrade(gradeLevel: String): Int? =
        Regex("\\d+").find(gradeLevel)?.value?.toIntOrNull()

    /** Maps the provider's question type onto the exams enum ("MULTIPLE_CHOICE" -> MCQ). */
    private fun examQuestionType(raw: String): QuestionType {
        val normalized = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
        if (normalized == "MULTIPLE_CHOICE") return QuestionType.MCQ
        return runCatching { QuestionType.valueOf(normalized) }.getOrDefault(QuestionType.MCQ)
    }

    /**
     * Keyed question JSON for a QUIZ block, mirroring the shape LearningService
     * authors. The mapper builds it so question text can never break the JSON.
     */
    private fun quizMetadataJson(questions: List<ContentUnitQuestionEntity>): String {
        val payload = questions.map { q ->
            val options = codec.parseList(q.options) ?: emptyList<String>()
            val entry = linkedMapOf<String, Any?>(
                "text" to q.text,
                "type" to q.qType,
                "options" to options,
                "correctAnswer" to q.correctAnswer,
                "explanation" to q.explanation,
                "points" to q.points,
                "difficulty" to q.difficulty,
                "matchingPairs" to (codec.parseMap(q.matchingPairs) ?: emptyMap<String, String>()),
            )
            // The Android MiniQuiz parser reads `questions[].correct` as a 0-based option
            // index and silently ignores an option list without it, so a quiz projected
            // with only `correctAnswer` renders no answers. Emit the index the client
            // parses as well; the learner projection still strips both keys.
            optionIndex(options, q.correctAnswer)?.let { entry["correct"] = it }
            entry
        }
        return mapper.writeValueAsString(mapOf("questions" to payload))
    }

    /**
     * Resolves the stored answer to the 0-based option index the client parses
     * (`questions[].correct`). Accepts the option text, a single A-Z letter or a
     * 1-based option number; null when the question has no options or the key does
     * not identify one, so the client treats it as ungradable rather than wrong.
     */
    private fun optionIndex(options: List<String>, answer: String?): Int? {
        val key = answer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (options.isEmpty()) return null
        options.indexOfFirst { it.trim().equals(key, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.let { return it }
        val token = key.lowercase()
        if (token.length == 1 && token[0] in 'a'..'z') {
            val index = token[0] - 'a'
            if (index < options.size) return index
        }
        token.toIntOrNull()?.let { number ->
            if (number in 1..options.size) return number - 1
        }
        return null
    }

    /**
     * learning_posts and readable_files reference users, so generated content needs
     * a real author row. One stable, inactive system author backs every projection;
     * it is created lazily so a fresh database can project without a seed.
     */
    private fun systemAuthorId(): UUID {
        if (!users.existsById(SYSTEM_AUTHOR_ID)) {
            users.save(
                UserEntity().apply {
                    id = SYSTEM_AUTHOR_ID
                    name = SYSTEM_AUTHOR_NAME
                    passwordHash = ""
                    role = Role.ADMIN
                    isActive = false
                    isVerified = true
                }
            )
        }
        return SYSTEM_AUTHOR_ID
    }

    private companion object {
        const val REVIEWED = "REVIEWED"
        const val PUBLISHED = "PUBLISHED"
        const val DRAFT = "DRAFT"
        const val SYSTEM_AUTHOR_NAME = "BrainBox Study Team"
        val SYSTEM_AUTHOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000A1")

        /** Practice-paper projection shaping. */
        const val MIN_PAPER_MINUTES = 30
        const val MINUTES_PER_QUESTION = 3
        const val MIN_DIFFICULTY = 1
        const val MAX_DIFFICULTY = 5
        const val DEFAULT_DIFFICULTY = 3
        const val MAX_CLIENT_ID_CHARS = 80
        const val GENERATED_CLIENT_PREFIX = "gen:"
    }
}
