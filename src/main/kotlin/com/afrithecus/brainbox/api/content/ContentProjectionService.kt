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
import com.afrithecus.brainbox.api.exams.QuestionCodec
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
import java.util.UUID

/** What a [ContentProjectionService.project] call wrote, for the caller to route on. */
data class ProjectionResult(
    /** POST for a book-like unit, FILE for a chunk. */
    val kind: String,
    val postId: UUID? = null,
    val fileId: UUID? = null,
)

/**
 * Phase 7.3: projects the internal content_units cache into the client-facing
 * tables. A book-like unit (NOTES/BOOK/QUIZ/FLASHCARDS) becomes a learning_posts
 * row plus learning_content blocks; a CHUNK becomes a readable_files row with an
 * inline body. Projection is idempotent by unit id: re-projecting updates the same
 * rows and replaces the content blocks rather than duplicating them.
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
    private val users: UserRepository,
    private val codec: QuestionCodec,
    private val mapper: ObjectMapper,
) {

    @Transactional
    fun project(unitId: UUID): ProjectionResult {
        val unit = contentUnits.findById(unitId).orElse(null)
            ?: throw notFound("Content unit not found")
        val concept = unit.conceptId?.let { concepts.findById(it).orElse(null) }
        val reviewed = unit.reviewState == REVIEWED
        return when (unit.taskType.trim().uppercase()) {
            "NOTES", "BOOK", "QUIZ", "FLASHCARDS" -> projectBook(unit, concept?.name, reviewed)
            "CHUNK" -> projectChunk(unit, concept?.name, reviewed)
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

    // ------------------------------------------------------------ internals

    private fun strandName(conceptId: UUID?): String? {
        if (conceptId == null) return null
        return curriculumMaps
            .findFirstByConceptIdAndCountryCodeAndCurriculumOrderBySortOrderAsc(conceptId, "KE", "CBC")
            ?.strandName
    }

    /**
     * Keyed question JSON for a QUIZ block, mirroring the shape LearningService
     * authors. The mapper builds it so question text can never break the JSON.
     */
    private fun quizMetadataJson(questions: List<ContentUnitQuestionEntity>): String {
        val payload = questions.map { q ->
            mapOf(
                "text" to q.text,
                "type" to q.qType,
                "options" to (codec.parseList(q.options) ?: emptyList<String>()),
                "correctAnswer" to q.correctAnswer,
                "explanation" to q.explanation,
                "points" to q.points,
                "difficulty" to q.difficulty,
                "matchingPairs" to (codec.parseMap(q.matchingPairs) ?: emptyMap<String, String>()),
            )
        }
        return mapper.writeValueAsString(mapOf("questions" to payload))
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
    }
}
