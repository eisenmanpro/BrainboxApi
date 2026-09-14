package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitQuestionEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitStepEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitQuestionRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitStepRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningService
import com.afrithecus.brainbox.api.learning.model.ContentType
import com.afrithecus.brainbox.api.learning.model.FileType
import com.afrithecus.brainbox.api.learning.model.LearningScope
import com.afrithecus.brainbox.api.learning.repository.LearningContentRepository
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.ReadableFileRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Phase 7.3: generated content_units project into the client-facing tables.
 * Book-like units become learning_posts + learning_content that LearningService
 * can serve, chunks become readable_files with an inline body, re-projection is
 * idempotent and unreviewed units stay hidden from learners. The hidden-projection
 * test disables the 7.5c machine-first gate explicitly so it tests state, not policy.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ContentProjectionTests(
    @Autowired private val projection: ContentProjectionService,
    @Autowired private val policy: ModerationPolicyService,
    @Autowired private val learningService: LearningService,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val unitSteps: ContentUnitStepRepository,
    @Autowired private val unitQuestions: ContentUnitQuestionRepository,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val contents: LearningContentRepository,
    @Autowired private val readables: ReadableFileRepository,
    @Autowired private val users: UserRepository,
) {

    @Test
    fun notesUnitProjectsToReadablePost() {
        val concept = seedConcept()
        seedCurriculum(concept)
        val unit = seedUnit(concept, taskType = "NOTES", reviewState = "REVIEWED", title = "Fractions Notes")
        seedStep(unit, 0, "Halves", "One half is one of two equal parts.")
        seedStep(unit, 1, "Quarters", "One quarter is one of four equal parts.")
        seedQuestion(unit, "Which is larger, 1/2 or 1/4?", correctAnswer = "1/2")

        val result = projection.project(unit.id)
        check(result.kind == "POST")
        check(result.postId == unit.id)
        check(result.fileId == null)

        val stored = contents.findAllByPostIdOrderByOrderIndexAsc(unit.id)
        check(stored.size == 3)
        check(stored.count { it.contentType == ContentType.NOTES } == 2)
        val quiz = stored.single { it.contentType == ContentType.QUIZ }
        check(quiz.orderIndex == 2)
        check(quiz.metadata != null)
        check(quiz.metadata!!.contains("Which is larger"))
        check(quiz.metadata!!.contains("correctAnswer"))
        check(quiz.metadata!!.contains("difficulty"))

        val post = posts.findById(unit.id).orElseThrow()
        check(post.subject == "MATHEMATICS")
        check(post.customSubjectName == null)
        check(post.title == "Fractions Notes")
        check(post.topic == "Fractions")
        check(post.subtopic == null)
        check(post.gradeLevel == "Grade 4")
        check(post.cbcStrand == "Numbers")
        check(post.scope == LearningScope.GLOBAL)
        check(post.status == "PUBLISHED")
        check(post.isPublished)
        check(post.imageUrl == null)

        val learner = learner()
        val detail = learningService.detail(learner, unit.id.toString())
        check(detail.subject == "MATHEMATICS")
        check(detail.title == "Fractions Notes")
        check(detail.topic == "Fractions")
        check(detail.cbcStrand == "Numbers")
        check(detail.status == "PUBLISHED")
        check(detail.content != null)
        check(detail.content!!.size == 3)
        check(detail.content!!.count { it.type == "NOTES" } >= 2)
        check(detail.content!!.all { it.postId == unit.id.toString() })
        val clientQuiz = detail.content!!.single { it.type == "QUIZ" }
        check(!clientQuiz.metadata!!.contains("correctAnswer"))
    }

    @Test
    fun chunkUnitProjectsToReadableFile() {
        val concept = seedConcept()
        seedCurriculum(concept)
        val text = "A short chunk about fractions."
        val unit = seedUnit(concept, taskType = "CHUNK", reviewState = "REVIEWED", title = "Fraction Chunk", body = text)

        val result = projection.project(unit.id)
        check(result.kind == "FILE")
        check(result.fileId == unit.id)
        check(result.postId == null)

        val file = readables.findById(unit.id).orElseThrow()
        check(file.title == "Fraction Chunk")
        check(file.body == text)
        check(file.authorName == "Brainbox")
        check(file.subject == "MATHEMATICS")
        check(file.category == "Notes")
        check(file.docType == "PLAINTEXT")
        check(file.fileUrl == "")
        check(file.fileType == FileType.TXT)
        check(file.pageCount == 0)
        check(file.sizeBytes == text.toByteArray(StandardCharsets.UTF_8).size.toLong())
        check(file.scope == LearningScope.GLOBAL)
        check(file.isActive)
    }

    @Test
    fun reprojectingIsIdempotent() {
        val concept = seedConcept()
        seedCurriculum(concept)
        val unit = seedUnit(concept, taskType = "NOTES", reviewState = "REVIEWED", title = "Fractions Notes")
        seedStep(unit, 0, "One", "Body one")
        seedStep(unit, 1, "Two", "Body two")
        seedQuestion(unit, "Q1", correctAnswer = "A")

        projection.project(unit.id)
        val first = contents.findAllByPostIdOrderByOrderIndexAsc(unit.id).size
        check(first == 3)

        projection.project(unit.id)
        val second = contents.findAllByPostIdOrderByOrderIndexAsc(unit.id).size
        check(second == first)
        check(posts.findById(unit.id).orElseThrow().title == "Fractions Notes")
    }

    @Test
    fun unreviewedUnitsProjectHidden() {
        // Phase 7.5c auto-approval is on by default; disable it so this test still
        // proves an unreviewed unit stays hidden for the reason under test.
        policy.set("auto_approve_enabled", "false")
        val concept = seedConcept()
        seedCurriculum(concept)

        val book = seedUnit(concept, taskType = "NOTES", reviewState = "UNREVIEWED", title = "Draft Notes")
        seedStep(book, 0, "One", "Draft body")
        val bookResult = projection.project(book.id)
        check(bookResult.kind == "POST")
        val post = posts.findById(book.id).orElseThrow()
        check(!post.isPublished)
        check(post.status == "DRAFT")
        check(posts.findAllByIsPublishedTrue().none { it.id == book.id })

        val learner = learner()
        val error = assertFailsWith<ApiException> { learningService.detail(learner, book.id.toString()) }
        check(error.code == ApiErrorCode.NOT_FOUND)

        val chunk = seedUnit(concept, taskType = "CHUNK", reviewState = "UNREVIEWED", title = "Draft Chunk", body = "draft")
        projection.project(chunk.id)
        check(!readables.findById(chunk.id).orElseThrow().isActive)
    }

    private fun seedConcept(): ConceptEntity =
        concepts.save(
            ConceptEntity().apply {
                code = "TST-FRAC-01"
                name = "Fractions"
                description = "Compare, order and compute with fractions."
                subject = "Mathematics"
                sortOrder = 1
            }
        )

    private fun seedCurriculum(concept: ConceptEntity): CurriculumMapEntity =
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "ALL"
                strandCode = "MAT-NUM"
                strandName = "Numbers"
                learningOutcome = "Compare fractions with unlike denominators."
                sortOrder = 1
            }
        )

    private fun seedUnit(
        concept: ConceptEntity,
        taskType: String,
        reviewState: String,
        title: String?,
        body: String? = null,
    ): ContentUnitEntity =
        contentUnits.save(
            ContentUnitEntity().apply {
                generationKey = "test:" + UUID.randomUUID()
                this.taskType = taskType
                conceptId = concept.id
                subject = "Mathematics"
                gradeLevel = "Grade 4"
                this.title = title
                this.body = body
                authorName = "Brainbox"
                this.reviewState = reviewState
            }
        )

    private fun seedStep(unit: ContentUnitEntity, orderIndex: Int, title: String, body: String) {
        unitSteps.save(
            ContentUnitStepEntity().apply {
                unitId = unit.id
                this.orderIndex = orderIndex
                this.title = title
                this.body = body
            }
        )
    }

    private fun seedQuestion(unit: ContentUnitEntity, text: String, correctAnswer: String) {
        unitQuestions.save(
            ContentUnitQuestionEntity().apply {
                unitId = unit.id
                orderIndex = 0
                qType = "MULTIPLE_CHOICE"
                this.text = text
                options = null
                this.correctAnswer = correctAnswer
                explanation = "Because."
                points = 2
                difficulty = 4
            }
        )
    }

    private fun learner(): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = "07" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                email = UUID.randomUUID().toString() + "@projection.test"
                passwordHash = "not-a-hash"
                name = "Projection Learner"
                role = Role.STUDENT
                isActive = true
                isVerified = true
            }
        )
}
