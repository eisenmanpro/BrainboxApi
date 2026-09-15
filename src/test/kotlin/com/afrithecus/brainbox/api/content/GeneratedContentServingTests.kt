package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.auth.web.AuthResponse
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.batch.ContentBatchRequest
import com.afrithecus.brainbox.api.content.batch.ContentBatchService
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.ContentUnitEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.exams.model.ExamStatus
import com.afrithecus.brainbox.api.exams.model.ExamType
import com.afrithecus.brainbox.api.exams.repository.ExamRepository
import com.afrithecus.brainbox.api.exams.web.CreateExamQuestionRequest
import com.afrithecus.brainbox.api.exams.web.CreateExamRequest
import com.afrithecus.brainbox.api.exams.web.ExamContentPayload
import com.afrithecus.brainbox.api.exams.web.ExamDetail
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.model.ContentType
import com.afrithecus.brainbox.api.learning.repository.LearningContentRepository
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
import com.afrithecus.brainbox.api.learning.repository.ReadableFileRepository
import com.afrithecus.brainbox.api.learning.web.LearningContentPayload
import com.afrithecus.brainbox.api.learning.web.ReadableFilePayload
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 7 serving verification: content generated through the real pipeline
 * (batch producer -> durable queue -> worker -> fake router -> validators/safety
 * -> auto-approval -> projection) must actually reach a learner through the
 * existing client read endpoints, not just exist in the internal cache.
 *
 * It also pins the reviewed-only guard: an unreviewed (gated) unit never reaches
 * the learner through the same endpoints, and the projected quiz metadata uses the
 * `questions[].correct` index the Android MiniQuiz parser reads.
 */
@SpringBootTest(properties = ["app.content.run-mode=both", "app.content.worker.retry-backoff-seconds=0"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(ContentRouterTests.FakeProviderConfig::class)
class GeneratedContentServingTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val batch: ContentBatchService,
    @Autowired private val jobService: GenerationJobService,
    @Autowired private val worker: GenerationJobWorker,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val contents: LearningContentRepository,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val readables: ReadableFileRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val examRepository: ExamRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
    @Autowired private val entityManager: EntityManager,
) {

    private val fake: ContentRouterTests.FakeContentGenerationProvider
        get() = provider as ContentRouterTests.FakeContentGenerationProvider

    private lateinit var topic: ConceptEntity
    private var sortOrder = 0

    @BeforeEach
    fun setUp() {
        fake.requests.clear()
        fake.failNext = null
        fake.clean = false
        fake.verificationRequests.clear()
        fake.verificationCalls = 0
        fake.verifyFailNext = null
        fake.verificationAnswers = null

        val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
        val strand = concept("MAT-STR-" + suffix, "Numbers", "Mathematics", null)
        val sub = concept("MAT-SUB-" + suffix, "Fractions and decimals", "Mathematics", strand.id)
        topic = concept("MAT-TOP-" + suffix, "Fractions", "Mathematics", sub.id)
        map(topic)
    }

    @Test
    fun `generated notes and quiz reach the learner through the content endpoint`() {
        fake.clean = true
        val summary = batch.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = "Grade 4",
                subject = "Mathematics",
                taskTypes = listOf("NOTES", "QUIZ"),
                limit = 1,
            )
        )
        check(summary.jobsEnqueued == 2) { "expected one NOTES and one QUIZ job, got " + summary.jobsEnqueued }

        worker.poll()
        flushAndClear()

        val notesUnit = storedUnit("NOTES")
        val quizUnit = storedUnit("QUIZ")
        check(notesUnit.reviewState == "REVIEWED") { "clean NOTES must auto-approve, was " + notesUnit.reviewState }
        check(quizUnit.reviewState == "REVIEWED") { "clean QUIZ must auto-approve, was " + quizUnit.reviewState }

        val token = learnerToken()
        for (unit in listOf(notesUnit, quizUnit)) {
            val blocks = learnerBlocks(token, unit.id.toString())
            check(blocks.isNotEmpty()) { "no content served for " + unit.taskType }

            val notesBlocks = blocks.filter { it.type == "NOTES" }
            check(notesBlocks.size == 3) { "expected three NOTES blocks, got " + notesBlocks.size }
            check(notesBlocks.map { it.orderIndex } == listOf(0, 1, 2)) { "NOTES must be served in orderIndex order" }
            check(notesBlocks.all { it.postId == unit.id.toString() }) { "post id must be the content unit id" }
            check(notesBlocks.all { !it.content.isNullOrBlank() }) { "generated NOTES bodies must not be blank" }
            check(notesBlocks.first().content!!.contains("First step body")) { "the generated body must be served" }

            val quizBlock = blocks.single { it.type == "QUIZ" }
            check(quizBlock.postId == unit.id.toString())
            check(quizBlock.orderIndex == 3)
            val questions = objectMapper.readTree(requireNotNull(quizBlock.metadata)).get("questions")
            check(questions != null && questions.size() == 8) { "generated QUIZ must carry its questions" }
            check(questions.get(0).get("text").asString() == "Clean question 0?")
        }
    }

    @Test
    fun `projected quiz metadata carries the client correct index`() {
        fake.clean = true
        batch.enqueueBatch(
            ContentBatchRequest(gradeLevel = "Grade 4", subject = "Mathematics", taskTypes = listOf("QUIZ"), limit = 1)
        )
        worker.poll()
        flushAndClear()

        val unit = storedUnit("QUIZ")
        val quiz = contents.findAllByPostIdOrderByOrderIndexAsc(unit.id).single { it.contentType == ContentType.QUIZ }
        val stored = objectMapper.readTree(requireNotNull(quiz.metadata)).get("questions")
        check(stored.get(0).get("correct").asInt() == 0) { "stored metadata must use the client correct index" }
        check(stored.get(0).get("correctAnswer").asString() == "A")

        // The learner projection must still withhold the key in both spellings.
        val learnerMetadata = learnerBlocks(learnerToken(), unit.id.toString()).single { it.type == "QUIZ" }.metadata!!
        val learnerQuestions = objectMapper.readTree(learnerMetadata).get("questions")
        check(learnerQuestions.get(0).get("correct") == null) { "the answer key must be stripped for learners" }
        check(learnerQuestions.get(0).get("correctAnswer") == null)
    }

    @Test
    fun `generated chunk reaches the learner with its inline body`() {
        fake.clean = true
        val request = batch.buildRequest(topic, "CHUNK", "Grade 4", "en", "v1")
        jobService.enqueue(request, GenerationJobSource.BATCH)
        worker.poll()
        flushAndClear()

        val unit = requireNotNull(contentUnits.findByGenerationKey(request.generationKey))
        check(unit.reviewState == "REVIEWED") { "clean CHUNK must auto-approve, was " + unit.reviewState }

        val response = mockMvc.perform(
            get("/materials/readable/" + unit.id).header("Authorization", auth(learnerToken()))
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val payload = objectMapper.readValue(response, ReadableFilePayload::class.java)
        check(payload.body == "Clean body for " + request.generationKey) {
            "the projected inline body must be served, got " + payload.body
        }
        check(payload.fileType == "PLAINTEXT")
        check(payload.filePath.isEmpty()) { "a chunk points at no external file" }
        check(payload.title == "Fractions")
        check(payload.totalPages == 0)
    }

    @Test
    fun `unreviewed generated units stay hidden from learners`() {
        // The non-clean fake result has two steps, a lesson-rule BLOCKER, so the
        // auto-approval gate refuses it and projection writes it hidden.
        fake.clean = false
        batch.enqueueBatch(
            ContentBatchRequest(gradeLevel = "Grade 4", subject = "Mathematics", taskTypes = listOf("NOTES"), limit = 1)
        )
        val chunkRequest = batch.buildRequest(topic, "CHUNK", "Grade 4", "en", "v1")
        jobService.enqueue(chunkRequest, GenerationJobSource.BATCH)
        worker.poll()
        flushAndClear()

        val notesUnit = storedUnit("NOTES")
        check(notesUnit.reviewState == "UNREVIEWED") { "a gated unit must not be auto-approved" }
        val post = posts.findById(notesUnit.id).orElseThrow()
        check(!post.isPublished && post.status == "DRAFT")

        val token = learnerToken()
        mockMvc.perform(
            get("/learning/post/" + notesUnit.id + "/content").header("Authorization", auth(token))
        ).andExpect(status().isNotFound)

        val chunkUnit = requireNotNull(contentUnits.findByGenerationKey(chunkRequest.generationKey))
        check(chunkUnit.reviewState == "UNREVIEWED")
        check(!readables.findById(chunkUnit.id).orElseThrow().isActive)
        mockMvc.perform(
            get("/materials/readable/" + chunkUnit.id).header("Authorization", auth(token))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `published practice paper content still serves`() {
        val admin = adminToken()
        val examId = createPracticePaper(admin)

        val response = mockMvc.perform(
            get("/practice-papers/" + examId + "/content").header("Authorization", auth(learnerToken()))
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val content = objectMapper.readValue(response, ExamContentPayload::class.java)
        check(content.examId == examId)
        check(content.cover.subject == "Mathematics")
        check(content.sections.single().questions.single().text == "2 + 2?")
        check(content.markingScheme.questionAnswers.isNotEmpty())
    }

    @Test
    fun `generated practice paper is served to a learner with its marking scheme`() {
        fake.clean = true
        val summary = batch.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = "Grade 4",
                subject = "Mathematics",
                taskTypes = listOf("PRACTICE_PAPER"),
                limit = 1,
            )
        )
        check(summary.jobsEnqueued == 1)
        check(summary.shelfJobsEnqueued == 1)

        worker.poll()
        flushAndClear()

        val key = batch.practicePaperKey("Grade 4", "Mathematics", 1, "en", "v1")
        val unit = requireNotNull(contentUnits.findByGenerationKey(key))
        check(unit.reviewState == "REVIEWED") { "a clean practice paper must auto-approve" }

        // The projection wrote a real exam row of the practice-paper type.
        val exam = examRepository.findById(unit.id).orElseThrow()
        check(exam.examType == ExamType.PRACTICE_PAPER)
        check(exam.status == ExamStatus.PUBLISHED)
        check(exam.questionCount == 8)

        val response = mockMvc.perform(
            get("/practice-papers/" + unit.id + "/content").header("Authorization", auth(learnerToken()))
        ).andExpect(status().isOk).andReturn().response.contentAsString

        val content = objectMapper.readValue(response, ExamContentPayload::class.java)
        check(content.examId == unit.id.toString())
        check(content.cover.subject == "Mathematics")
        check(content.cover.year == exam.examYear)
        check(content.cover.questionCount == 8)
        check(content.sections.single().questions.size == 8)
        check(content.sections.single().questions.first().correctAnswer == "A")
        check(content.markingScheme.questionAnswers.values.all { it == "A" })
        check(content.markingScheme.totalMarks == 16)
    }

    @Test
    fun `unreviewed generated practice paper stays hidden from learners`() {
        // The non-clean fake result has only two questions, below the assessment
        // floor, so the auto-approval gate refuses it and projection writes a DRAFT.
        fake.clean = false
        batch.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = "Grade 4",
                subject = "Mathematics",
                taskTypes = listOf("PRACTICE_PAPER"),
                limit = 1,
            )
        )
        worker.poll()
        flushAndClear()

        val key = batch.practicePaperKey("Grade 4", "Mathematics", 1, "en", "v1")
        val unit = requireNotNull(contentUnits.findByGenerationKey(key))
        check(unit.reviewState == "UNREVIEWED") { "a gated practice paper must not be auto-approved" }
        check(examRepository.findById(unit.id).orElseThrow().status == ExamStatus.DRAFT)

        mockMvc.perform(
            get("/practice-papers/" + unit.id + "/content").header("Authorization", auth(learnerToken()))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `generated study guide reaches the learner as learning-post steps`() {
        fake.clean = true
        val summary = batch.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = "Grade 4",
                subject = "Mathematics",
                taskTypes = listOf("STUDY_GUIDE"),
                limit = 1,
            )
        )
        check(summary.jobsEnqueued == 1)

        worker.poll()
        flushAndClear()

        val key = batch.studyGuideKey("Grade 4", "Mathematics", "en", "v1")
        val unit = requireNotNull(contentUnits.findByGenerationKey(key))
        check(unit.reviewState == "REVIEWED") { "a clean study guide must auto-approve" }

        val notes = learnerBlocks(learnerToken(), unit.id.toString()).filter { it.type == "NOTES" }
        check(notes.size == 3) { "expected the guide's three steps as NOTES blocks, got " + notes.size }
        check(notes.map { it.orderIndex } == listOf(0, 1, 2))
        check(notes.first().content!!.contains("First step body"))
    }

    // ------------------------------------------------------------ helpers

    private fun storedUnit(taskType: String): ContentUnitEntity {
        val key = batch.generationKey("Grade 4", topic.code, taskType, "en", "v1")
        return requireNotNull(contentUnits.findByGenerationKey(key)) { "no generated unit for " + key }
    }

    private fun learnerBlocks(token: String, postId: String): List<LearningContentPayload> {
        val body = mockMvc.perform(
            get("/learning/post/" + postId + "/content").header("Authorization", auth(token))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, Array<LearningContentPayload>::class.java).toList()
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun concept(code: String, name: String, subject: String, parentId: UUID?): ConceptEntity =
        concepts.save(
            ConceptEntity().apply {
                this.code = code
                this.name = name
                this.subject = subject
                this.parentId = parentId
                this.sortOrder = sortOrder++
            }
        )

    private fun map(concept: ConceptEntity) {
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                gradeLevel = "Grade 4"
                strandCode = "MAT4-NUM-01"
                strandName = "Numbers"
                learningOutcome = "Compare fractions with unlike denominators."
                sortOrder = sortOrder++
            }
        )
    }

    private fun auth(token: String) = "Bearer " + token

    private fun learnerToken(): String {
        val n = nextLearner.incrementAndGet()
        val phone = "079" + (1000000 + n)
        val body = "{\"name\":\"Serving Learner " + n + "\",\"phoneNumber\":\"" + phone +
            "\",\"password\":\"password123\",\"role\":\"STUDENT\"}"
        val response = mockMvc.perform(
            post("/auth/signup").header("X-Device-Id", "serving-device")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java).sessionToken!!
    }

    private fun adminToken(): String {
        val n = nextAdmin.incrementAndGet()
        val email = "serving-admin" + n + "@generated.test"
        users.save(
            UserEntity().apply {
                phoneNumber = "078" + (2000000 + n)
                this.email = email
                passwordHash = passwordEncoder.encode("adminpass123") ?: error("encode")
                name = "Serving Admin"
                role = Role.ADMIN
                isActive = true
                isVerified = true
            }
        )
        val response = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + email + "\",\"password\":\"adminpass123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, AuthResponse::class.java).sessionToken!!
    }

    private fun createPracticePaper(admin: String): String {
        val request = CreateExamRequest(
            title = "Serving Practice Paper",
            subject = "Mathematics",
            examType = "PRACTICE_PAPER",
            durationMinutes = 60,
            examYear = 2024,
            questions = listOf(
                CreateExamQuestionRequest(
                    text = "2 + 2?", type = "MCQ", options = listOf("3", "4"),
                    correctAnswer = "4", explanation = "sum", points = 2, topic = "Algebra",
                ),
            ),
        )
        val response = mockMvc.perform(
            post("/admin/exams").header("Authorization", auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(response, ExamDetail::class.java).id
    }

    private companion object {
        val nextLearner = AtomicInteger(0)
        val nextAdmin = AtomicInteger(0)
    }
}
