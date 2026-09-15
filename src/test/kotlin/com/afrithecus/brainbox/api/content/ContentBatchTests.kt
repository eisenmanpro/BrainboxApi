package com.afrithecus.brainbox.api.content

import com.afrithecus.brainbox.api.common.error.ApiErrorCode
import com.afrithecus.brainbox.api.common.error.ApiException
import com.afrithecus.brainbox.api.content.ai.ContentGenerationProvider
import com.afrithecus.brainbox.api.content.batch.ContentBatchCandidates
import com.afrithecus.brainbox.api.content.batch.ContentBatchRequest
import com.afrithecus.brainbox.api.content.batch.ContentBatchService
import com.afrithecus.brainbox.api.content.batch.ContentBatchSummary
import com.afrithecus.brainbox.api.content.entity.ConceptEntity
import com.afrithecus.brainbox.api.content.entity.CurriculumMapEntity
import com.afrithecus.brainbox.api.content.repository.ConceptRepository
import com.afrithecus.brainbox.api.content.repository.ContentUnitRepository
import com.afrithecus.brainbox.api.content.repository.CurriculumMapRepository
import com.afrithecus.brainbox.api.content.repository.GenerationJobRepository
import com.afrithecus.brainbox.api.content.web.BatchEnqueueRequest
import com.afrithecus.brainbox.api.identity.entity.UserEntity
import com.afrithecus.brainbox.api.identity.model.Role
import com.afrithecus.brainbox.api.identity.repository.UserRepository
import com.afrithecus.brainbox.api.learning.LearningService
import com.afrithecus.brainbox.api.learning.repository.LearningPostRepository
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
import kotlin.test.assertFailsWith

/**
 * Phase 7.5e Tier 1 batch producer. The service is exercised against the same
 * fake provider as the router tests so the end-to-end path (enqueue -> worker ->
 * router -> projection -> auto-approval) never touches the network.
 */
@SpringBootTest(properties = ["app.content.run-mode=both", "app.content.worker.retry-backoff-seconds=0"])
@ActiveProfiles("test")
@Transactional
@Import(ContentRouterTests.FakeProviderConfig::class)
class ContentBatchServiceTests(
    @Autowired private val service: ContentBatchService,
    @Autowired private val provider: ContentGenerationProvider,
    @Autowired private val worker: GenerationJobWorker,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val generationJobs: GenerationJobRepository,
    @Autowired private val contentUnits: ContentUnitRepository,
    @Autowired private val posts: LearningPostRepository,
    @Autowired private val learningService: LearningService,
    @Autowired private val users: UserRepository,
    @Autowired private val entityManager: EntityManager,
) {

    private val fake: ContentRouterTests.FakeContentGenerationProvider
        get() = provider as ContentRouterTests.FakeContentGenerationProvider

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

        val matStrand = concept("MAT-STR-01", "Numbers", "Mathematics", null)
        val matSub = concept("MAT-SUB-01", "Fractions and decimals", "Mathematics", matStrand.id)
        val fractions = concept("MAT-TOP-01", "Fractions", "Mathematics", matSub.id)
        val decimals = concept("MAT-TOP-02", "Decimals", "Mathematics", matSub.id)
        val algebra = concept("MAT-TOP-03", "Algebra", "Mathematics", matSub.id)
        map(fractions, "Grade 4")
        map(decimals, "Grade 4")
        map(algebra, "Grade 5")

        val engStrand = concept("ENG-STR-01", "Language", "English", null)
        val engSub = concept("ENG-SUB-01", "Grammar", "English", engStrand.id)
        val nouns = concept("ENG-TOP-01", "Nouns", "English", engSub.id)
        map(nouns, "Grade 4")
    }

    @Test
    fun `resolveTopicConcepts returns the leaf topics for the grade and subject`() {
        check(service.resolveTopicConcepts("Grade 4", "Mathematics").map { it.code } == listOf("MAT-TOP-01", "MAT-TOP-02"))
        check(service.resolveTopicConcepts("Grade 4", "English").map { it.code } == listOf("ENG-TOP-01"))
        check(service.resolveTopicConcepts("Grade 5", "Mathematics").map { it.code } == listOf("MAT-TOP-03"))
        check(service.resolveTopicConcepts("Grade 4").map { it.code } == listOf("MAT-TOP-01", "MAT-TOP-02", "ENG-TOP-01"))
        check(service.countCandidates("Grade 4", "Mathematics") == 2)
    }

    @Test
    fun `enqueueBatch enqueues one job per topic and task type with deterministic keys`() {
        val summary = service.enqueueBatch(
            ContentBatchRequest(gradeLevel = "Grade 4", subject = "Mathematics", limit = 50)
        )

        check(summary.subject == "Mathematics")
        check(summary.gradeLevel == "Grade 4")
        check(summary.taskTypes == listOf("NOTES", "QUIZ"))
        check(summary.conceptsMatched == 2)
        check(summary.conceptsQueued == 2)
        check(summary.jobsEnqueued == 4)
        check(summary.jobsAlreadyPresent == 0)
        check(!summary.truncated)

        check(service.generationKey("Grade 4", "MAT-TOP-01", "NOTES", "en", "v1") == "ke:cbc:grade4:mat-top-01:notes:en:v1")
        check(service.generationKey("Grade 4", "MAT-TOP-01", "QUIZ", "en", "v1") == "ke:cbc:grade4:mat-top-01:quiz:en:v1")

        val notes = generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc("ke:cbc:grade4:mat-top-01:notes:en:v1").single()
        check(notes.taskType == "NOTES")
        check(notes.gradeLevel == "Grade 4")
        check(notes.status == "QUEUED")
        check(notes.attempts == 0)
        check(notes.source == "BATCH")
        check(notes.requestPayload!!.contains("Fractions"))

        val quiz = generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc("ke:cbc:grade4:mat-top-01:quiz:en:v1").single()
        check(quiz.taskType == "QUIZ")
        check(quiz.source == "BATCH")
    }

    @Test
    fun `re-running the batch enqueues nothing new and keeps one row per key`() {
        val request = ContentBatchRequest(gradeLevel = "Grade 4", subject = "Mathematics", limit = 50)
        val first = service.enqueueBatch(request)
        val second = service.enqueueBatch(request)

        check(first.jobsEnqueued == 4)
        check(second.jobsEnqueued == 0)
        check(second.jobsAlreadyPresent == 4)
        check(second.conceptsMatched == 2)

        val keys = listOf(
            "ke:cbc:grade4:mat-top-01:notes:en:v1",
            "ke:cbc:grade4:mat-top-01:quiz:en:v1",
            "ke:cbc:grade4:mat-top-02:notes:en:v1",
            "ke:cbc:grade4:mat-top-02:quiz:en:v1",
        )
        keys.forEach { key ->
            check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).size == 1) {
                "expected exactly one job row for " + key
            }
        }
    }

    @Test
    fun `the limit truncates candidates and reports the full matched count`() {
        val summary = service.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = "Grade 4",
                subject = "Mathematics",
                taskTypes = listOf("NOTES"),
                limit = 1,
            )
        )

        check(summary.conceptsMatched == 2)
        check(summary.conceptsQueued == 1)
        check(summary.jobsEnqueued == 1)
        check(summary.truncated)
        check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc("ke:cbc:grade4:mat-top-01:notes:en:v1").size == 1)
        check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc("ke:cbc:grade4:mat-top-02:notes:en:v1").isEmpty())
    }

    @Test
    fun `a blank grade or an unknown task type is invalid argument`() {
        val blankGrade = assertFailsWith<ApiException> {
            service.enqueueBatch(ContentBatchRequest(gradeLevel = "   "))
        }
        check(blankGrade.code == ApiErrorCode.INVALID_ARGUMENT)

        val unknownTask = assertFailsWith<ApiException> {
            service.enqueueBatch(
                ContentBatchRequest(gradeLevel = "Grade 4", subject = "Mathematics", taskTypes = listOf("FLASHCARDS"))
            )
        }
        check(unknownTask.code == ApiErrorCode.INVALID_ARGUMENT)

        check(generationJobs.count() == 0L)
    }

    @Test
    fun `the batch path generates projects auto-approves and publishes for a clean unit`() {
        fake.clean = true
        val summary = service.enqueueBatch(
            ContentBatchRequest(
                gradeLevel = "Grade 4",
                subject = "Mathematics",
                taskTypes = listOf("NOTES"),
                limit = 1,
            )
        )
        check(summary.jobsEnqueued == 1)

        worker.poll()

        entityManager.flush()
        entityManager.clear()

        val key = "ke:cbc:grade4:mat-top-01:notes:en:v1"
        val unit = requireNotNull(contentUnits.findByGenerationKey(key))
        check(unit.reviewState == "REVIEWED") { "expected REVIEWED, got " + unit.reviewState }

        val post = posts.findById(unit.id).orElseThrow()
        check(post.isPublished)
        check(post.status == "PUBLISHED")
        check(post.title == "Fractions")

        val detail = learningService.detail(seedLearner(), unit.id.toString())
        check(detail.isPublished)
        check(detail.title == "Fractions")

        check(generationJobs.findAllByGenerationKeyOrderByCreatedAtAsc(key).single().status == "SUCCEEDED")
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

    private fun map(concept: ConceptEntity, gradeLevel: String) {
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                this.gradeLevel = gradeLevel
                strandCode = "MAT-STR-01"
                strandName = "Numbers"
                sortOrder = sortOrder++
            }
        )
    }

    private fun seedLearner(): UserEntity {
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        return users.save(
            UserEntity().apply {
                phoneNumber = "07" + suffix.substring(0, 8)
                email = "batch-" + suffix + "@generation.test"
                passwordHash = "not-a-real-hash"
                name = "Batch Learner"
                role = Role.STUDENT
                isActive = true
                isVerified = true
            }
        )
    }
}

/**
 * Phase 7.5e admin surface: ADMIN only, blank grade and unknown task types are
 * 400, and the candidates call sizes the run.
 */
@SpringBootTest(properties = ["app.content.run-mode=api"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ContentBatchControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val concepts: ConceptRepository,
    @Autowired private val curriculumMaps: CurriculumMapRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val passwordEncoder: PasswordEncoder,
) {

    private var sortOrder = 0

    @BeforeEach
    fun setUp() {
        val strand = concept("MAT-STR-01", "Numbers", "Mathematics", null)
        val sub = concept("MAT-SUB-01", "Fractions and decimals", "Mathematics", strand.id)
        map(concept("MAT-TOP-01", "Fractions", "Mathematics", sub.id), "Grade 4")
        map(concept("MAT-TOP-02", "Decimals", "Mathematics", sub.id), "Grade 4")
    }

    @Test
    fun `an admin enqueues the batch and gets the summary`() {
        val admin = seed("0744610401", Role.ADMIN, "Batch Admin")
        val body = objectMapper.writeValueAsString(
            BatchEnqueueRequest(gradeLevel = "Grade 4", subject = "Mathematics")
        )

        val summary = read(
            mockMvc.perform(
                post("/admin/content/batch")
                    .header("Authorization", auth(token(admin)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentBatchSummary::class.java,
        )

        check(summary.gradeLevel == "Grade 4")
        check(summary.subject == "Mathematics")
        check(summary.conceptsMatched == 2)
        check(summary.conceptsQueued == 2)
        check(summary.jobsEnqueued == 4)
        check(summary.jobsAlreadyPresent == 0)
        check(!summary.truncated)
    }

    @Test
    fun `a blank grade level is rejected as invalid argument`() {
        val admin = seed("0744610402", Role.ADMIN, "Batch Admin")
        mockMvc.perform(
            post("/admin/content/batch")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gradeLevel\":\"   \",\"subject\":\"Mathematics\"}")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `an unknown task type is rejected as invalid argument`() {
        val admin = seed("0744610403", Role.ADMIN, "Batch Admin")
        val body = objectMapper.writeValueAsString(
            BatchEnqueueRequest(gradeLevel = "Grade 4", subject = "Mathematics", taskTypes = listOf("VIDEO"))
        )
        mockMvc.perform(
            post("/admin/content/batch")
                .header("Authorization", auth(token(admin)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `the batch surface requires an admin token`() {
        val body = objectMapper.writeValueAsString(BatchEnqueueRequest(gradeLevel = "Grade 4"))

        mockMvc.perform(
            post("/admin/content/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isUnauthorized)

        val teacher = seed("0744610404", Role.TEACHER, "Batch Teacher")
        mockMvc.perform(
            post("/admin/content/batch")
                .header("Authorization", auth(token(teacher)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/admin/content/batch/candidates?gradeLevel=Grade 4").header("Authorization", auth(token(teacher)))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `candidates returns the matched concept count`() {
        val admin = seed("0744610405", Role.ADMIN, "Batch Admin")
        val candidates = read(
            mockMvc.perform(
                get("/admin/content/batch/candidates")
                    .param("gradeLevel", "Grade 4")
                    .param("subject", "Mathematics")
                    .header("Authorization", auth(token(admin)))
            ).andExpect(status().isOk).andReturn().response.contentAsString,
            ContentBatchCandidates::class.java,
        )

        check(candidates.gradeLevel == "Grade 4")
        check(candidates.subject == "Mathematics")
        check(candidates.conceptsMatched == 2)
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

    private fun map(concept: ConceptEntity, gradeLevel: String) {
        curriculumMaps.save(
            CurriculumMapEntity().apply {
                conceptId = concept.id
                countryCode = "KE"
                curriculum = "CBC"
                this.gradeLevel = gradeLevel
                strandCode = "MAT-STR-01"
                strandName = "Numbers"
                sortOrder = sortOrder++
            }
        )
    }

    private fun seed(phone: String, role: Role, name: String): UserEntity =
        users.save(
            UserEntity().apply {
                phoneNumber = phone
                email = phone + "@batch.test"
                passwordHash = passwordEncoder.encode("password123") ?: error("encode")
                this.name = name
                this.role = role
                isActive = true
                isVerified = true
            }
        )

    private fun token(user: UserEntity): String {
        val body = mockMvc.perform(
            post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + user.email + "\",\"password\":\"password123\"}")
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return objectMapper.readValue(body, com.afrithecus.brainbox.api.auth.web.AuthResponse::class.java).sessionToken!!
    }

    private fun auth(token: String) = "Bearer " + token

    private fun <T> read(body: String, type: Class<T>): T = objectMapper.readValue(body, type)
}
